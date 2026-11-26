package com.yarmiplaytv.media.subtitles

import com.yarmiplaytv.media.MediaSourceException
import com.yarmiplaytv.media.SharedHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Online subtitle search combining OpenSubtitles.com REST API v1 and Subdl API v1.
 * Never makes network calls unless explicitly invoked by the user (feature is off by default).
 */
class SubtitleSearchClient(
    private val openSubtitlesApiKey: String = "",
    private val subdlApiKey: String = "",
    http: OkHttpClient? = null,
    private val openSubtitlesBaseUrl: String = "https://api.opensubtitles.com/api/v1",
    private val subdlApiBaseUrl: String = "https://api.subdl.com/api/v1",
    private val subdlDownloadBaseUrl: String = "https://dl.subdl.com",
    private val userAgent: String = "YarmiplayTV v3.3.0",
) {
    private val http: OkHttpClient = http ?: SharedHttp.base.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    @Volatile
    var openSubtitlesToken: String? = null

    val hasOpenSubtitles: Boolean get() = openSubtitlesApiKey.isNotBlank()
    val hasSubdl: Boolean get() = subdlApiKey.isNotBlank()
    val configured: Boolean get() = hasOpenSubtitles || hasSubdl

    /** Signs in to OpenSubtitles.com (optional, for higher daily download limits). */
    suspend fun loginOpenSubtitles(username: String, password: String): OpenSubtitlesAccount = withContext(Dispatchers.IO) {
        if (!hasOpenSubtitles) throw MediaSourceException("OpenSubtitles is not configured in this build")
        val payload = json.encodeToString(OsLoginRequest.serializer(), OsLoginRequest(username.trim(), password))
        val req = Request.Builder()
            .url("${openSubtitlesBaseUrl.trimEnd('/')}/login")
            .header("Api-Key", openSubtitlesApiKey)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw MediaSourceException(
                    if (res.code == 401) "Wrong OpenSubtitles username or password" else "OpenSubtitles login failed (${res.code})",
                    res.code,
                )
            }
            val parsed = json.decodeFromString(OsLoginResponse.serializer(), body)
            val tok = parsed.token?.takeIf { it.isNotBlank() }
                ?: throw MediaSourceException("OpenSubtitles did not return a token")
            openSubtitlesToken = tok
            OpenSubtitlesAccount(username.trim(), tok)
        }
    }

    /** Searches all configured providers in parallel and merges results (exact hash matches first, then by downloads). */
    suspend fun search(query: SubtitleSearchQuery): List<SubtitleSearchResult> = coroutineScope {
        if (!configured) throw MediaSourceException("Online subtitle search keys are not configured in this build")
        val osDeferred = if (hasOpenSubtitles) async { runCatching { searchOpenSubtitles(query) } } else null
        val sdDeferred = if (hasSubdl) async { runCatching { searchSubdl(query) } } else null
        val osRes = osDeferred?.await()
        val sdRes = sdDeferred?.await()
        val results = ArrayList<SubtitleSearchResult>()
        osRes?.getOrNull()?.let { results += it }
        sdRes?.getOrNull()?.let { results += it }
        if (results.isEmpty()) {
            val err = osRes?.exceptionOrNull() ?: sdRes?.exceptionOrNull()
            if (err != null) throw err
        }
        results.sortedWith(
            compareByDescending<SubtitleSearchResult> { it.exactHashMatch }
                .thenByDescending { it.downloads },
        )
    }

    /** Downloads [result] and extracts the subtitle file if zipped. */
    suspend fun download(result: SubtitleSearchResult): DownloadedSubtitle = when (result.provider) {
        SubtitleProviderKind.OPENSUBTITLES -> downloadOpenSubtitles(result)
        SubtitleProviderKind.SUBDL -> downloadSubdl(result)
    }

    private suspend fun searchOpenSubtitles(q: SubtitleSearchQuery): List<SubtitleSearchResult> = withContext(Dispatchers.IO) {
        val urlBuilder = "${openSubtitlesBaseUrl.trimEnd('/')}/subtitles".toHttpUrl().newBuilder()
        q.movieHash?.takeIf { it.length == 16 }?.let { urlBuilder.addQueryParameter("moviehash", it) }
        q.query.trim().takeIf { it.isNotEmpty() }?.let { urlBuilder.addQueryParameter("query", it) }
        val lang = q.language.trim().lowercase()
        if (lang.isNotEmpty() && lang != "all") urlBuilder.addQueryParameter("languages", lang)
        q.season?.let { urlBuilder.addQueryParameter("season_number", it.toString()) }
        q.episode?.let { urlBuilder.addQueryParameter("episode_number", it.toString()) }
        val req = Request.Builder()
            .url(urlBuilder.build())
            .header("Api-Key", openSubtitlesApiKey)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .apply { openSubtitlesToken?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .build()
        try {
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw MediaSourceException("OpenSubtitles answered ${res.code}", res.code)
                val parsed = json.decodeFromString(OsSearchResponse.serializer(), text)
                parsed.data.mapNotNull { item ->
                    val attrs = item.attributes ?: return@mapNotNull null
                    val file = attrs.files.firstOrNull() ?: return@mapNotNull null
                    val name = attrs.release?.takeIf { it.isNotBlank() }
                        ?: file.fileName?.takeIf { it.isNotBlank() }
                        ?: "Subtitle ${item.id}"
                    val ext = file.fileName?.substringAfterLast('.', "")?.lowercase().orEmpty().ifEmpty { "srt" }
                    SubtitleSearchResult(
                        id = "os:${file.fileId}",
                        provider = SubtitleProviderKind.OPENSUBTITLES,
                        releaseName = name,
                        language = attrs.language ?: q.language,
                        format = ext,
                        downloads = attrs.downloadCount ?: 0,
                        hearingImpaired = attrs.hearingImpaired == true,
                        exactHashMatch = attrs.moviehashMatch == true,
                        fileId = file.fileId,
                    )
                }
            }
        } catch (e: IOException) {
            throw MediaSourceException("Cannot reach OpenSubtitles (${e.message})", cause = e)
        }
    }

    private suspend fun downloadOpenSubtitles(result: SubtitleSearchResult): DownloadedSubtitle = withContext(Dispatchers.IO) {
        val fileId = result.fileId ?: throw MediaSourceException("Missing OpenSubtitles file id")
        val payload = json.encodeToString(OsDownloadRequest.serializer(), OsDownloadRequest(fileId))
        val req = Request.Builder()
            .url("${openSubtitlesBaseUrl.trimEnd('/')}/download")
            .header("Api-Key", openSubtitlesApiKey)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .apply { openSubtitlesToken?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        val (link, fileName) = http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val msg = when (res.code) {
                    406 -> "OpenSubtitles daily download limit reached (sign in under Settings for more)"
                    401 -> "OpenSubtitles session expired"
                    else -> "OpenSubtitles download failed (${res.code})"
                }
                throw MediaSourceException(msg, res.code)
            }
            val parsed = json.decodeFromString(OsDownloadResponse.serializer(), text)
            val url = parsed.link?.takeIf { it.isNotBlank() }
                ?: throw MediaSourceException(parsed.message ?: "OpenSubtitles did not return a download link")
            url to (parsed.fileName?.takeIf { it.isNotBlank() } ?: "${result.releaseName}.${result.format}")
        }
        val bytes = http.newCall(Request.Builder().url(link).header("User-Agent", userAgent).build()).execute().use { res ->
            if (!res.isSuccessful) throw MediaSourceException("Subtitle file download failed (${res.code})", res.code)
            res.body?.bytes() ?: throw MediaSourceException("Empty subtitle download")
        }
        extractSubtitle(bytes, fileName, result.language)
    }

    private suspend fun searchSubdl(q: SubtitleSearchQuery): List<SubtitleSearchResult> = withContext(Dispatchers.IO) {
        val urlBuilder = "${subdlApiBaseUrl.trimEnd('/')}/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", subdlApiKey)
            .addQueryParameter("subs_per_page", "20")
        val query = q.query.trim()
        if (query.isNotEmpty()) urlBuilder.addQueryParameter("film_name", query)
        q.fileName?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("file_name", it) }
        val lang = q.language.trim().lowercase()
        if (lang.isNotEmpty() && lang != "all") urlBuilder.addQueryParameter("languages", subdlLangCode(lang))
        if (q.season != null || q.episode != null) {
            urlBuilder.addQueryParameter("type", "tv")
            q.season?.let { urlBuilder.addQueryParameter("season_number", it.toString()) }
            q.episode?.let { urlBuilder.addQueryParameter("episode_number", it.toString()) }
        }
        val req = Request.Builder().url(urlBuilder.build()).header("Accept", "application/json").build()
        try {
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw MediaSourceException("Subdl answered ${res.code}", res.code)
                val parsed = json.decodeFromString(SubdlResponse.serializer(), text)
                parsed.subtitles.mapNotNull { sub ->
                    val path = sub.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val name = sub.releaseName?.takeIf { it.isNotBlank() }
                        ?: sub.name?.takeIf { it.isNotBlank() }
                        ?: "Subdl subtitle"
                    SubtitleSearchResult(
                        id = "sd:$path",
                        provider = SubtitleProviderKind.SUBDL,
                        releaseName = name,
                        language = sub.lang?.lowercase() ?: q.language,
                        format = "srt",
                        downloads = sub.downloadsCount ?: 0,
                        hearingImpaired = sub.hi == true,
                        exactHashMatch = false,
                        downloadPath = path,
                    )
                }
            }
        } catch (e: IOException) {
            throw MediaSourceException("Cannot reach Subdl (${e.message})", cause = e)
        }
    }

    private suspend fun downloadSubdl(result: SubtitleSearchResult): DownloadedSubtitle = withContext(Dispatchers.IO) {
        val path = result.downloadPath ?: throw MediaSourceException("Missing Subdl download path")
        val url = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            "${subdlDownloadBaseUrl.trimEnd('/')}/${path.trimStart('/')}"
        }
        val bytes = http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { res ->
            if (!res.isSuccessful) throw MediaSourceException("Subdl download failed (${res.code})", res.code)
            res.body?.bytes() ?: throw MediaSourceException("Empty Subdl archive")
        }
        extractSubtitle(bytes, "${result.releaseName}.srt", result.language)
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val SUB_EXTS = setOf("ass", "ssa", "srt", "vtt", "sub")

        /** Converts ISO code to Subdl's uppercase language code (e.g. `en` -> `EN`, `pt-br` -> `BR_PT`). */
        fun subdlLangCode(code: String): String = when (code.lowercase()) {
            "pt-br" -> "BR_PT"
            "zh-cn", "zh-tw" -> "ZH"
            else -> code.substringBefore('-').uppercase()
        }

        /** Extracts the best subtitle entry if [bytes] is a ZIP archive, otherwise returns [bytes] directly. */
        fun extractSubtitle(bytes: ByteArray, fallbackName: String, language: String): DownloadedSubtitle {
            if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                    var bestName: String? = null
                    var bestBytes: ByteArray? = null
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext in SUB_EXTS) {
                            val data = zis.readBytes()
                            if (bestBytes == null || ext == "ass" || ext == "srt") {
                                bestName = name
                                bestBytes = data
                                if (ext == "ass") break
                            }
                        }
                    }
                    if (bestBytes != null && bestName != null) {
                        return DownloadedSubtitle(bestName, language, bestBytes)
                    }
                }
                throw MediaSourceException("No subtitle file found inside the archive")
            }
            return DownloadedSubtitle(fallbackName, language, bytes)
        }
    }
}

@Serializable
private data class OsLoginRequest(val username: String, val password: String)

@Serializable
private data class OsLoginResponse(val token: String? = null)

@Serializable
private data class OsSearchResponse(val data: List<OsItem> = emptyList())

@Serializable
private data class OsItem(val id: String = "", val attributes: OsAttributes? = null)

@Serializable
private data class OsAttributes(
    val language: String? = null,
    val release: String? = null,
    @SerialName("download_count") val downloadCount: Int? = null,
    @SerialName("hearing_impaired") val hearingImpaired: Boolean? = null,
    @SerialName("moviehash_match") val moviehashMatch: Boolean? = null,
    val files: List<OsFile> = emptyList(),
)

@Serializable
private data class OsFile(
    @SerialName("file_id") val fileId: Long = 0,
    @SerialName("file_name") val fileName: String? = null,
)

@Serializable
private data class OsDownloadRequest(@SerialName("file_id") val fileId: Long)

@Serializable
private data class OsDownloadResponse(
    val link: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    val message: String? = null,
)

@Serializable
private data class SubdlResponse(
    val status: Boolean = false,
    val subtitles: List<SubdlItem> = emptyList(),
)

@Serializable
private data class SubdlItem(
    val name: String? = null,
    @SerialName("release_name") val releaseName: String? = null,
    val url: String? = null,
    val lang: String? = null,
    val hi: Boolean? = null,
    @SerialName("downloads_count") val downloadsCount: Int? = null,
)
