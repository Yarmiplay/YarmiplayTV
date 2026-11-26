package com.yarmiplaytv.tracking

import com.yarmiplaytv.Logger
import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.local.LocalLibrary
import com.yarmiplaytv.media.FileNames
import com.yarmiplaytv.media.subtitles.OpenSubtitlesAccount
import com.yarmiplaytv.media.subtitles.OpenSubtitlesHash
import com.yarmiplaytv.media.subtitles.SubtitleLanguages
import com.yarmiplaytv.media.subtitles.SubtitleSearchClient
import com.yarmiplaytv.media.subtitles.SubtitleSearchQuery
import com.yarmiplaytv.media.subtitles.SubtitleSearchResult
import com.yarmiplaytv.relay.RelayManager
import com.yarmiplaytv.sync.NowPlaying
import com.yarmiplaytv.sync.PlaylistController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/**
 * Online subtitle search coordinator: builds smart queries (including OpenSubtitles 64-bit moviehash when
 * playing a local or complete cached file), downloads results into the app's cache folder, and attaches
 * them immediately to the player via [PlaylistController.addExternalSubtitle].
 */
class OnlineSubtitlesService(
    val client: SubtitleSearchClient,
    private val settingsStore: SettingsStore,
    private val settings: StateFlow<AppSettings>,
    private val local: LocalLibrary,
    private val relay: RelayManager?,
    private val playlist: PlaylistController,
    cacheDir: File,
) {
    private val dir = File(cacheDir, "subtitles").apply { mkdirs() }

    val configured: Boolean get() = client.configured

    init {
        client.openSubtitlesToken = settings.value.openSubtitlesAccount?.token
    }

    /** Builds the initial search query from [nowPlaying], respecting the saved default language (`en`). */
    suspend fun defaultQuery(nowPlaying: NowPlaying?): SubtitleSearchQuery = withContext(Dispatchers.IO) {
        val lang = settings.value.playback.onlineSubtitlesLanguage.ifBlank { SubtitleLanguages.DEFAULT }
        if (nowPlaying == null) return@withContext SubtitleSearchQuery("", lang)
        val ep = nowPlaying.episode
        val parsed = FileNames.parse(nowPlaying.fileName)
        val title = ep?.seriesName?.takeIf { it.isNotBlank() } ?: parsed.title.ifBlank { nowPlaying.title }
        val hash = computeHash(nowPlaying)
        SubtitleSearchQuery(
            query = title,
            language = lang,
            season = ep?.season,
            episode = ep?.episode,
            movieHash = hash,
            fileSize = nowPlaying.sizeBytes.takeIf { it > 0L },
            fileName = nowPlaying.fileName,
        )
    }

    suspend fun search(query: SubtitleSearchQuery): List<SubtitleSearchResult> {
        client.openSubtitlesToken = settings.value.openSubtitlesAccount?.token
        return client.search(query)
    }

    /** Downloads [result], writes it into the subtitle cache, and attaches & selects it in the player. */
    suspend fun downloadAndAttach(result: SubtitleSearchResult): String {
        client.openSubtitlesToken = settings.value.openSubtitlesAccount?.token
        val downloaded = client.download(result)
        val file = withContext(Dispatchers.IO) {
            dir.mkdirs()
            val safe = downloaded.fileName.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "subtitle.srt" }
            val target = File(dir, "${System.currentTimeMillis()}_$safe")
            target.writeBytes(downloaded.content)
            target
        }
        val title = "${result.releaseName.take(60)} (${result.provider.label})"
        playlist.addExternalSubtitle(file.absolutePath, title, downloaded.language)
        Logger.i(TAG, "Attached online subtitle ${file.name} from ${result.provider.label}")
        return downloaded.fileName
    }

    suspend fun signInOpenSubtitles(username: String, password: String): OpenSubtitlesAccount {
        val account = client.loginOpenSubtitles(username, password)
        settingsStore.saveOpenSubtitlesAccount(account)
        return account
    }

    suspend fun signOutOpenSubtitles() {
        client.openSubtitlesToken = null
        settingsStore.saveOpenSubtitlesAccount(null)
    }

    private fun computeHash(np: NowPlaying): String? {
        // Complete relayed cache file
        relay?.completeFile(np.url)?.let { path ->
            OpenSubtitlesHash.compute(path)?.let { return it }
        }
        // Local file:// URI
        if (np.url.startsWith("file:", ignoreCase = true)) {
            runCatching { File(URI(np.url)).absolutePath }.getOrNull()?.let { path ->
                OpenSubtitlesHash.compute(path)?.let { return it }
            }
        }
        // Local library channel (handles Android content:// SAF URIs too)
        return runCatching {
            local.openChannel(np.url).use { ch -> OpenSubtitlesHash.compute(ch) }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "OnlineSubtitles"
    }
}
