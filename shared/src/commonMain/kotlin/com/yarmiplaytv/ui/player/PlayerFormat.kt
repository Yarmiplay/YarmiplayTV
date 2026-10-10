package com.yarmiplaytv.ui.player

import com.yarmiplaytv.player.PlaybackState
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

/** Under the seek bar, between the clocks: codec, height, hardware or software decoding, and speed when not 1×. */
fun playbackInfo(state: PlaybackState): String = listOfNotNull(
    state.videoCodec?.substringBefore(' ')?.uppercase(),
    if (state.videoHeight > 0) "${state.videoHeight}p" else null,
    state.hwdec?.takeIf { it.isNotBlank() && it != "no" }?.let { "HW" } ?: if (state.fileLoaded) "SW" else null,
    if (state.speed != 1.0) "%.2fx".format(state.speed) else null,
).joinToString(" · ")

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
