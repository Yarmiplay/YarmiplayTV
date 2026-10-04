package com.yarmiplaytv.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A newer version on the download page for one platform. */
data class Update(
    val version: String,
    /** The download page, for people to open in a browser. */
    val pageUrl: String,
    /** This platform's package (APK, .msi, .dmg or .deb). */
    val downloadUrl: String,
    val sha256: String?,
)

/** Versions like "1.2" or "1.2.0"; missing parts count as 0, so 1.2 and 1.2.0 are the same version. */
object AppVersions {
    fun parse(version: String): List<Int>? =
        version.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }

    /** False when either isn't a version (e.g. "dev"), so development builds never see updates. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/**
 * Reads the download page's version.json (written by scripts/download_site.py) from GitHub Pages:
 * `{"page": url, "platforms": {"android": {"version", "file", "sha256"}, "windows": …}}`, with "page"
 * and "file" relative to the manifest. The request carries nothing about the user or the device.
 */
class UpdateChecker(
    private val manifestUrl: String = System.getProperty("yarmiplaytv.updateManifest") ?: MANIFEST_URL,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
) {
    /** The update for [platform] when the page has a newer version than [current]; null otherwise or on any error. */
    suspend fun check(platform: String, current: String): Update? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(manifestUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                parse(response.body!!.string(), platform, current, manifestUrl)
            }
        }.getOrNull()
    }

    companion object {
        const val MANIFEST_URL = "https://tv.yarmiplay.com/version.json"

        fun parse(json: String, platform: String, current: String, manifestUrl: String): Update? {
            val root = Json.parseToJsonElement(json).jsonObject
            val entry = (root["platforms"] as? JsonObject)?.get(platform) as? JsonObject ?: return null
            val version = entry["version"]?.jsonPrimitive?.content ?: return null
            val file = entry["file"]?.jsonPrimitive?.content ?: return null
            if (!AppVersions.isNewer(version, current)) return null
            val base = manifestUrl.toHttpUrl()
            return Update(
                version = version,
                pageUrl = (base.resolve(root["page"]?.jsonPrimitive?.content ?: "./") ?: base).toString(),
                downloadUrl = base.resolve(file)?.toString() ?: return null,
                sha256 = entry["sha256"]?.jsonPrimitive?.content,
            )
        }
    }
}
