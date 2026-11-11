package com.yarmiplaytv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.media.FileNames
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.player.Player
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.yarmiplaytv.syncplay.FileDifference
import com.yarmiplaytv.syncplay.FileInfo
import com.yarmiplaytv.syncplay.FileMatch
import com.yarmiplaytv.syncplay.Filenames
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.syncplay.RoomUser
import com.yarmiplaytv.ui.mobile.formatSize

fun formatClock(seconds: Double): String {
    if (seconds.isNaN() || seconds < 0) return "0:00"
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * True when picture-in-picture should treat this as a video. A spectrum on an audio-only file
 * reports a picture size, and that must not pull music into the video window.
 */
/**
 * The playback snapshot the window should recompose on.
 *
 * The position, the A/V offset and the drop counters change many times a second. The seek bar
 * polls [Player.currentPosition] on its own, so those fields must not invalidate the player.
 * The cache is quantized to a quarter second, which is finer than the "Ns cached" label.
 * [showStats] keeps the diagnostic fields, with the A/V offset rounded to 50 ms, so turning
 * stats on still doesn't recompose on every frame.
 */
fun PlaybackState.forControls(showStats: Boolean): PlaybackState {
    val cache = kotlin.math.round(cacheSeconds * 4.0) / 4.0
    val sync = if (showStats) avSync?.let { kotlin.math.round(it * 20.0) / 20.0 } else null
    val latency = if (showStats) displayLatencyMs?.let { kotlin.math.round(it) } else null
    val drops = if (showStats) frameDrops else null
    val decoder = if (showStats) decoderFrameDrops else null
    if (position == 0.0 && cacheSeconds == cache && avSync == sync && displayLatencyMs == latency &&
        frameDrops == drops && decoderFrameDrops == decoder
    ) {
        return this
    }
    return copy(
        position = 0.0,
        cacheSeconds = cache,
        avSync = sync,
        displayLatencyMs = latency,
        frameDrops = drops,
        decoderFrameDrops = decoder,
    )
}

/** [forControls] with the diagnostics hidden. */
fun PlaybackState.forUi(): PlaybackState = forControls(showStats = false)

/** Collects [Player.state] as [forControls], so a frame of playback does not recompose this caller. */
@Composable
fun Player.collectPlayback(showStats: Boolean = false): PlaybackState {
    val flow = remember(this, showStats) {
        state.map { it.forControls(showStats) }.distinctUntilChanged()
    }
    return flow.collectAsStateWithLifecycle(state.value.forControls(showStats)).value
}

fun playbackShowsVideo(state: PlaybackState, name: String = state.url.orEmpty()): Boolean =
    state.fileLoaded && !state.audioOnly &&
        (state.videoWidth > 0 || state.displayAspect > 0.0 || !FileNames.isAudio(name))

/** Under the seek bar, between the clocks: codec, height, hardware or software decoding, and speed when not 1×. */
fun playbackInfo(state: PlaybackState, showStats: Boolean = false): String = listOfNotNull(
    if (state.audioOnly) null else state.videoCodec?.substringBefore(' ')?.uppercase(),
    if (!state.audioOnly && state.videoHeight > 0) "${state.videoHeight}p" else null,
    if (state.audioOnly) null else (state.hwdec?.takeIf { it.isNotBlank() && it != "no" }?.let { "HW" } ?: if (state.fileLoaded) "SW" else null),
    if (state.speed != 1.0) "%.2fx".format(state.speed) else null,
    if (showStats) playbackStats(state) else null,
).joinToString(" · ")

/** Dropped frames / A-V offset / display latency when the player reports them (desktop). */
fun playbackStats(state: PlaybackState): String? {
    val parts = listOfNotNull(
        state.frameDrops?.let { "drop $it" },
        state.decoderFrameDrops?.takeIf { it > 0 }?.let { "dec-drop $it" },
        state.avSync?.let { "A/V %+.0fms".format(it * 1000) },
        state.displayLatencyMs?.takeIf { it > 0 }?.let { "disp %.0fms".format(it) },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** Under a room member's file: how it differs from ours, like Syncplay's warning, or null when it looks the same. */
fun fileDifferenceNote(theirs: FileInfo?, mine: FileInfo?): String? {
    if (theirs == null || mine == null) return null
    val differences = FileMatch.differences(theirs, mine).ifEmpty { return null }
    val what = joinWords(differences.sorted().map { it.name.lowercase() })
    val sizes = if (FileDifference.SIZE in differences) sizesText(theirs, mine)?.let { (a, b) -> ": $a vs $b" } else null
    return "Different $what than yours${sizes.orEmpty()}"
}

/** Others in the room playing [entry] from a file whose size differs from ours; empty unless we play [entry] too. */
fun RoomState.sizeMismatches(entry: String): List<RoomUser> {
    val mine = users.firstOrNull { it.name == username }?.file ?: return emptyList()
    if (!Filenames.same(mine.name, entry)) return emptyList()
    return others.filter { user ->
        val theirs = user.file ?: return@filter false
        Filenames.same(theirs.name, entry) && !FileMatch.sameSize(theirs, mine)
    }
}

/** The shared playlist's warning on [entry] when someone plays it from a file of a different size than ours. */
fun RoomState.sizeWarning(entry: String): String? {
    val mismatched = sizeMismatches(entry).ifEmpty { return null }
    val mine = users.first { it.name == username }.file ?: return null
    val whose = joinWords(mismatched.map { "${it.name}'s" })
    val sizes = mismatched.singleOrNull()?.file?.let { sizesText(it, mine) }
    return "File size differs from $whose" + (sizes?.let { (a, b) -> ": $a vs your $b" } ?: "")
}

/** Both sizes, precise enough to tell apart; null unless both are known. */
private fun sizesText(theirs: FileInfo, mine: FileInfo): Pair<String, String>? {
    if (theirs.size <= 0 || mine.size <= 0) return null
    val short = formatSize(theirs.size) to formatSize(mine.size)
    return if (short.first != short.second) short else "%,d bytes".format(theirs.size) to "%,d bytes".format(mine.size)
}

private fun joinWords(words: List<String>): String =
    if (words.size <= 1) words.joinToString() else words.dropLast(1).joinToString(", ") + " and " + words.last()
