package com.yarmiplaytv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.yarmiplaytv.media.SkipSegment
import com.yarmiplaytv.player.Chapter
import com.yarmiplaytv.ui.mobile.ChapterMarks
import com.yarmiplaytv.ui.mobile.SkipMarks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage

/** Chapter ticks on the seek bar, including over an opening, and none when the file has no chapters. */
@OptIn(ExperimentalTestApi::class)
class ChapterMarksUiTest {
    @Test
    fun `ticks sit on the chapter starts and the opening stays marked`() = runComposeUiTest {
        setContent {
            Box(Modifier.size(1000.dp, 12.dp).background(Color.DarkGray)) {
                SkipMarks(
                    listOf(SkipSegment(SkipSegment.Kind.OPENING, 100.0, 200.0)),
                    duration = 1000.0,
                    modifier = Modifier.matchParentSize(),
                )
                ChapterMarks(
                    listOf(
                        Chapter("Cold open", 100.0),
                        Chapter("Cold open", 100.02),
                        Chapter("Part A", 500.0),
                        Chapter(null, 900.0),
                        Chapter("Too early", -5.0),
                        Chapter("Too late", 5000.0),
                        Chapter("Not a time", Double.NaN),
                    ),
                    duration = 1000.0,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        onNodeWithTag("chapter_marks").assertExists()
        onNodeWithTag("skip_marks").assertExists()
        val image = onRoot().captureToImage().toAwtImage() as BufferedImage
        assertTrue("chapter at 10%", image.tickAt(0.10))
        assertTrue("opening between chapters stays amber", image.amberAt(0.15))
        assertFalse("no chapter in the middle of nowhere", image.tickAt(0.30))
        assertTrue("chapter at halfway", image.tickAt(0.50))
        assertTrue("untitled chapter still has a tick", image.tickAt(0.90))
        assertFalse("a chapter past the end is not drawn at the end", image.tickAt(0.98))
    }

    @Test
    fun `a file without chapters draws no ticks`() = runComposeUiTest {
        setContent {
            Box(Modifier.size(400.dp, 12.dp).background(Color.DarkGray)) {
                ChapterMarks(emptyList(), duration = 1000.0, modifier = Modifier.matchParentSize())
            }
        }
        onNodeWithTag("chapter_marks").assertDoesNotExist()
    }

    private fun BufferedImage.tickAt(fraction: Double): Boolean {
        val x = (width * fraction).toInt().coerceIn(0, width - 1)
        val y = height / 2
        return (x - 3..x + 3).any { column ->
            val pixel = getRGB(column.coerceIn(0, width - 1), y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            r > 230 && g > 230 && b > 230
        }
    }

    private fun BufferedImage.amberAt(fraction: Double): Boolean {
        val pixel = getRGB((width * fraction).toInt().coerceIn(0, width - 1), height / 2)
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return r > 180 && g > 120 && b < 140 && r > b
    }
}
