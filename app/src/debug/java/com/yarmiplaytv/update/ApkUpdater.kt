package com.yarmiplaytv.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.yarmiplaytv.Logger
import com.yarmiplaytv.YarmiplayTvApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

/**
 * Installs updates from the download page's APK: downloads it, checks its SHA-256 against version.json and hands it
 * to the system's installer, which asks the person to confirm and replaces the app right away. Only in the download
 * page's build (src/debug); the Play build never installs apps.
 */
internal class ApkUpdater(
    private val context: Context,
    /** Shows that the installer failed, with the sentence to show. */
    private val onFailed: (Update, String) -> Unit,
) : UpdateInstaller {
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private var apk: File? = null
    private var update: Update? = null

    override val installsNow: Boolean get() = true

    override suspend fun download(update: Update, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val expected = update.sha256 ?: error("the download page lists no checksum")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "YarmiplayTV-${update.version}.apk")
        val partial = File(target.path + ".part")
        http.newCall(Request.Builder().url(update.downloadUrl).build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body!!
            body.byteStream().use { input -> copyVerified(input, body.contentLength(), expected, partial, progress) }
        }
        if (!partial.renameTo(target)) error("couldn't save the update")
        apk = target
        this@ApkUpdater.update = update
    }

    /** Hands the APK to the system's installer, which asks to confirm; Android closes the app while it installs. */
    override fun installAfterExit(relaunch: Boolean) {
        val apk = apk ?: return
        val update = update ?: return
        current = this
        thread(name = "update-install") {
            runCatching {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(apk.length())
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("YarmiplayTV.apk", 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out, 1 shl 16) }
                        session.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                    val result = PendingIntent.getBroadcast(
                        context, id, Intent(context, ApkInstallReceiver::class.java).putExtra(EXTRA_VERSION, update.version), flags,
                    )
                    session.commit(result.intentSender)
                }
            }.onFailure {
                Logger.w(TAG, "Couldn't start installing ${update.version}", it)
                onFailed(update, Updates.INSTALL_FAILED)
            }
        }
    }

    override fun pendingStep(): String? =
        if (context.packageManager.canRequestPackageInstalls()) null
        else "Allow YarmiplayTV to install updates: tap Install, turn on “Allow from this source”, then come back."

    override fun openPendingStep() {
        val manage = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        for (intent in listOf(manage, details)) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // Some TV boxes leave out the "install unknown apps" screen.
            }
        }
    }

    /** What the system's installer said about the session [installAfterExit] started. */
    fun onInstallResult(status: Int, version: String?, message: String?) {
        val update = update?.takeIf { it.version == version } ?: return
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Unit
            // Cancelled at the confirmation: Install works again from the notice.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Logger.i(TAG, "Installing ${update.version} was cancelled")
            else -> {
                Logger.w(TAG, "Installing ${update.version} failed ($status): $message")
                rememberFailedInstall(context, update.version)
                onFailed(update, Updates.INSTALL_FAILED)
            }
        }
    }

    companion object {
        private const val TAG = "ApkUpdater"
        const val EXTRA_VERSION = "com.yarmiplaytv.update.VERSION"
        private const val PREFS = "updates"
        private const val FAILED = "failed_install"

        /** The updater that started the last install, for the installer's answer. */
        @Volatile var current: ApkUpdater? = null

        /**
         * Copies [input] to [target] while checking its SHA-256 against [expected]; deletes [target] and throws when
         * they differ. [progress] gets 0 to 1 when [total] (the length, or -1) is known.
         */
        suspend fun copyVerified(input: InputStream, total: Long, expected: String, target: File, progress: (Float) -> Unit) {
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var percent = -1
            target.outputStream().use { out ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    done += n
                    val p = if (total > 0) (done * 100 / total).toInt() else 0
                    if (p != percent) progress(p / 100f).also { percent = p }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) {
                target.delete()
                error("the download is damaged (checksum mismatch)")
            }
        }

        private fun rememberFailedInstall(context: Context, version: String) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(FAILED, version).apply()

        /**
         * The version whose install failed last time, once: the next start shows why instead of offering it again
         * as new, and the start after that doesn't repeat it.
         */
        fun takeFailedInstall(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getString(FAILED, null)?.also { prefs.edit().remove(FAILED).apply() }
        }
    }
}

/** The system installer's answer about an update: asks to confirm, or says how it went. */
class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val updater = ApkUpdater.current
            ?: ((context.applicationContext as? YarmiplayTvApp)?.container?.updates?.installer as? ApkUpdater)
            ?: return
        updater.onInstallResult(status, intent.getStringExtra(ApkUpdater.EXTRA_VERSION), intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
