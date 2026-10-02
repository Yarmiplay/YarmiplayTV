package com.yarmiplaytv

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.yarmiplaytv.data.AppSettings
import kotlinx.coroutines.runBlocking

object TestSupport {
    const val CLIP_ASSET = "yarmiplaytv-test-clip.mp4"

    val app: YarmiplayTvApp get() = ApplicationProvider.getApplicationContext()
    val container: AppContainer get() = app.container

    /** Copies the bundled test clip into MediaStore (Movies/YarmiplayTV-tests) and returns its content:// URI. */
    fun insertClip(displayName: String): Uri {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/YarmiplayTV-tests")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)) { "MediaStore insert failed" }
        resolver.openOutputStream(uri)!!.use { out ->
            InstrumentationRegistry.getInstrumentation().context.assets.open(CLIP_ASSET).use { it.copyTo(out) }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    fun clipSize(): Long = InstrumentationRegistry.getInstrumentation().context.assets.open(CLIP_ASSET).use { it.readBytes().size.toLong() }

    fun delete(uri: Uri) {
        runCatching { app.contentResolver.delete(uri, null, null) }
    }

    /** Disconnects from Syncplay for the duration of a test; returns a function restoring the previous state. */
    fun isolateFromRoom(): () -> Unit {
        val saved: AppSettings = container.settings.value
        val wasActive = container.sync.isActive
        container.sync.disconnect()
        return {
            runBlocking { container.settingsStore.saveSyncplay(saved.syncplay, saved.autoConnect) }
            if (wasActive) container.sync.connect(saved.syncplay.toConfig())
        }
    }
}
