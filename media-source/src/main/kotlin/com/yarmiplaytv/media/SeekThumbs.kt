package com.yarmiplaytv.media

import java.net.URLEncoder

/** Text subtitle files the player can add beside a video. */
val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")

private val TEXT_SUBTITLE_CODECS = setOf("srt", "ass", "ssa", "vtt", "subrip", "webvtt")

/** One Jellyfin trickplay resolution: real frames, [intervalMs] apart, tiled [columns] by [rows]. */
data class TrickplaySet(
    val intervalMs: Int,
    val thumbWidth: Int,
    val thumbHeight: Int,
    val columns: Int,
    val rows: Int,
    val count: Int,
)

/** Where one frame sits in a trickplay sprite sheet. */
data class TrickplayTile(val sheet: Int, val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The frame at [seconds]: the nearest stored thumbnail, which sheet it is on, and the tile inside that sheet.
 * Null when the set has nothing to show.
 */
fun trickplayTile(set: TrickplaySet, seconds: Double): TrickplayTile? {
    if (set.intervalMs <= 0 || set.columns <= 0 || set.rows <= 0 || set.count <= 0) return null
    if (set.thumbWidth <= 0 || set.thumbHeight <= 0) return null
    val index = ((seconds.coerceAtLeast(0.0) * 1000.0) / set.intervalMs).toInt().coerceIn(0, set.count - 1)
    val perSheet = set.columns * set.rows
    val at = index % perSheet
    return TrickplayTile(
        sheet = index / perSheet,
        x = (at % set.columns) * set.thumbWidth,
        y = (at / set.columns) * set.thumbHeight,
        width = set.thumbWidth,
        height = set.thumbHeight,
    )
}

/**
 * The trickplay resolution to ask for: the smallest one at least [wantedWidth] wide, or the largest there is.
 * [widths] are the pixel widths Jellyfin keyed the sets by.
 */
fun trickplayWidth(widths: Collection<Int>, wantedWidth: Int = 320): Int? {
    val have = widths.filter { it > 0 }.sorted()
    return have.firstOrNull { it >= wantedWidth } ?: have.lastOrNull()
}

/** `Videos/{itemId}/Trickplay/{width}/{sheet}.jpg`, with the access token. */
fun jellyfinSheetUrl(server: String, itemId: String, width: Int, sheet: Int, token: String): String {
    val root = server.trimEnd('/')
    val key = URLEncoder.encode(token, "UTF-8")
    return "$root/Videos/$itemId/Trickplay/$width/$sheet.jpg?api_key=$key"
}

/** The part id in a Plex part key such as `/library/parts/12345/file.mkv`. */
fun plexPartId(partKey: String): String? = Regex("/library/parts/(\\d+)").find(partKey)?.groupValues?.get(1)

/** The JPEG Plex stored for the seek bar at [seconds], from an index it has generated. */
fun plexPreviewUrl(server: String, partId: String, indexId: String, token: String, seconds: Double): String {
    val root = server.trimEnd('/')
    val millis = (seconds.coerceAtLeast(0.0) * 1000.0).toLong()
    val key = URLEncoder.encode(token, "UTF-8")
    return "$root/library/parts/$partId/indexes/$indexId/$millis?X-Plex-Token=$key"
}

/**
 * Seek-bar frames a server has already stored. [Jellyfin] is a trickplay sprite; [Plex] is one JPEG per time
 * from a generated preview index.
 */
sealed interface StoredSeekFrames {
    data class Jellyfin(val server: String, val itemId: String, val width: Int, val token: String, val set: TrickplaySet) : StoredSeekFrames {
        fun sheetUrl(index: Int): String = jellyfinSheetUrl(server, itemId, width, index, token)
    }

    data class Plex(val server: String, val partId: String, val indexId: String, val token: String) : StoredSeekFrames {
        fun jpegUrl(seconds: Double): String = plexPreviewUrl(server, partId, indexId, token, seconds)
    }
}

/** A subtitle file next to a video, or one a server already lists as external. */
data class ExternalSubtitle(val url: String, val title: String, val language: String?)

/** [subtitleName] belongs beside [videoName]: the same name, or that name plus one language tag. */
fun sidecarMatches(videoName: String, subtitleName: String): Boolean {
    val ext = subtitleName.substringAfterLast('.', "").lowercase()
    if (ext !in SUBTITLE_EXTENSIONS) return false
    val videoStem = videoName.substringBeforeLast('.').lowercase()
    val stem = subtitleName.substringBeforeLast('.').lowercase()
    if (stem == videoStem) return true
    val lang = stem.substringAfterLast('.', "")
    val base = stem.substringBeforeLast('.')
    return base == videoStem && lang.isNotEmpty() && lang.length <= 8 && lang.all { it.isLetter() }
}

/** The language tag in `Episode.en.srt`, or null when the file is just `Episode.srt`. */
fun sidecarLanguage(fileName: String): String? {
    val stem = fileName.substringBeforeLast('.')
    val lang = stem.substringAfterLast('.', "")
    val base = stem.substringBeforeLast('.')
    if (base == stem || lang.isEmpty() || lang.length > 8 || lang.any { !it.isLetter() }) return null
    return lang
}

fun isTextSubtitleCodec(codec: String?): Boolean = codec?.lowercase() in TEXT_SUBTITLE_CODECS

/** Which auto-loaded sidecar to turn on: a preferred language when one matches, otherwise the first. */
fun preferredSidecar(subs: List<ExternalSubtitle>, languages: String): ExternalSubtitle? {
    if (subs.isEmpty()) return null
    val wanted = languages.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    return subs.firstOrNull { sub ->
        val lang = sub.language?.lowercase() ?: return@firstOrNull false
        wanted.any { want -> lang == want || lang.startsWith(want) }
    } ?: subs.first()
}
