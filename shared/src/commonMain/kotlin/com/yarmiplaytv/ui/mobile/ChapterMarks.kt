package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yarmiplaytv.player.Chapter

/**
 * One embedded chapter the seek bar can draw: where it starts, and the name to show while pointing there.
 * A missing title becomes "Chapter N" (its place among the chapters that are actually drawn). That is a label
 * for a boundary the file has, not a chapter the file didn't list.
 */
data class ChapterBoundary(val time: Double, val label: String)

/**
 * Chapters worth a mark on a bar of [duration] seconds.
 * Out-of-range, non-finite and blank files produce none. Times within [SAME_CHAPTER_BOUNDARY_SECONDS] of the
 * same start share one mark; their names are joined. The result is in time order.
 */
fun chapterBoundaries(chapters: List<Chapter>, duration: Double): List<ChapterBoundary> {
    if (!duration.isFinite() || duration <= 0.0) return emptyList()
    val kept = chapters.mapNotNull { chapter ->
        val time = chapter.time
        if (!time.isFinite()) return@mapNotNull null
        val at = when {
            time < -TIME_SLOP -> return@mapNotNull null
            time > duration + TIME_SLOP -> return@mapNotNull null
            time < 0.0 -> 0.0
            time > duration -> duration
            else -> time
        }
        at to chapter.title?.trim()?.takeIf { it.isNotEmpty() }
    }.sortedBy { it.first }
    if (kept.isEmpty()) return emptyList()
    val merged = mutableListOf<Pair<Double, MutableList<String>>>()
    for ((time, title) in kept) {
        val last = merged.lastOrNull()
        if (last != null && time - last.first <= SAME_CHAPTER_BOUNDARY_SECONDS) {
            if (title != null && title !in last.second) last.second += title
        } else {
            merged += time to mutableListOf<String>().apply { if (title != null) add(title) }
        }
    }
    return merged.mapIndexed { index, (time, titles) ->
        ChapterBoundary(
            time,
            when {
                titles.isEmpty() -> "Chapter ${index + 1}"
                titles.size == 1 -> titles[0]
                else -> titles.joinToString(" · ")
            },
        )
    }
}

/**
 * The chapter that has started by [time], or null when [time] is before the first one.
 * Pointing at a gap before the first chapter does not invent a name.
 */
fun chapterTitleAt(boundaries: List<ChapterBoundary>, time: Double): String? {
    if (!time.isFinite() || boundaries.isEmpty()) return null
    return boundaries.lastOrNull { it.time <= time }?.label
}

/** Near-duplicate starts, such as a chapter listed twice, are one mark. */
internal const val SAME_CHAPTER_BOUNDARY_SECONDS = 0.05

private const val TIME_SLOP = 0.001

/**
 * A tick at each chapter start, on top of the track (and of the opening and ending spans).
 * Ticks that would land on the same pixels share one line, so a run of boundaries stays a line rather than a block.
 */
@Composable
fun ChapterMarks(chapters: List<Chapter>, duration: Double, modifier: Modifier = Modifier) {
    val boundaries = remember(chapters, duration) { chapterBoundaries(chapters, duration) }
    if (boundaries.isEmpty() || !duration.isFinite() || duration <= 0.0) return
    Canvas(modifier.testTag("chapter_marks")) {
        val core = 2.dp.toPx().coerceAtLeast(2f)
        val pad = 1.dp.toPx()
        var drawn = -1f
        for (mark in boundaries) {
            val x = (mark.time / duration).coerceIn(0.0, 1.0).toFloat() * size.width
            if (drawn >= 0f && x - drawn < core + pad) continue
            drawn = x
            val left = (x - core / 2f).coerceIn(0f, (size.width - core).coerceAtLeast(0f))
            val outlineLeft = (left - pad).coerceAtLeast(0f)
            drawRect(CHAPTER_TICK_EDGE, Offset(outlineLeft, 0f), Size((left - outlineLeft) + core + pad, size.height))
            drawRect(CHAPTER_TICK, Offset(left, 0f), Size(core, size.height))
        }
    }
}

/** White, with a dark edge, so a tick reads on the accent, on the dim track, and on an amber opening. */
private val CHAPTER_TICK = Color.White
private val CHAPTER_TICK_EDGE = Color(0xFF101418)
