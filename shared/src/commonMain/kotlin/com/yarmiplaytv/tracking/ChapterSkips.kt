package com.yarmiplaytv.tracking

import com.yarmiplaytv.media.SkipSegment
import com.yarmiplaytv.player.Chapter

/**
 * The opening and ending a file's own chapters mark, by their names: "Opening", "OP", "Intro", "Opening Song",
 * "OP2"… and "Ending", "ED", "Credits", "Outro", "Ending Credits"… (also オープニング and エンディング). Each runs to the
 * next chapter (or the end of the file). One much too short or too long to be one isn't taken.
 */
object ChapterSkips {
    private val opening = Regex("""^(op|opening|intro|introduction|op ?\d+|opening ?\d+|opening (song|theme|credits|titles|sequence)|オープニング)$""")
    private val ending = Regex(
        """^(ed|ending|outro|credits|end credits|closing credits|ed ?\d+|ending ?\d+|ending (song|theme|credits|titles|sequence)|エンディング)$""",
    )
    private val separators = Regex("""[^\p{L}\p{N}]+""")

    fun from(chapters: List<Chapter>, duration: Double): List<SkipSegment> {
        val sorted = chapters.sortedBy { it.time }
        return sorted.mapIndexedNotNull { i, chapter ->
            val name = chapter.title.orEmpty().lowercase().replace(separators, " ").trim()
            val kind = when {
                opening.matches(name) -> SkipSegment.Kind.OPENING
                ending.matches(name) -> SkipSegment.Kind.ENDING
                else -> return@mapIndexedNotNull null
            }
            val end = sorted.getOrNull(i + 1)?.time ?: duration.takeIf { it > 0 } ?: return@mapIndexedNotNull null
            val length = end - chapter.time
            val longest = if (kind == SkipSegment.Kind.OPENING) MAX_OPENING_SECONDS else MAX_ENDING_SECONDS
            if (length < MIN_SECONDS || length > longest) return@mapIndexedNotNull null
            SkipSegment(kind, chapter.time, end)
        }
    }

    private const val MIN_SECONDS = 10.0
    private const val MAX_OPENING_SECONDS = 240.0
    private const val MAX_ENDING_SECONDS = 360.0
}
