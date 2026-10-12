package com.yarmiplaytv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.yarmiplaytv.media.aniskip.SkipSegment
import com.yarmiplaytv.tracking.SkipPrompt
import com.yarmiplaytv.ui.mobile.MobileTheme
import com.yarmiplaytv.ui.mobile.SkipSegmentButton
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** The player's Skip button: what it says, the desktop's key, and that pressing it skips. */
@OptIn(ExperimentalTestApi::class)
class SkipButtonUiTest {
    @Test
    fun `the Skip button names the part and its key, and skips when pressed`() = runComposeUiTest {
        var skipped = 0
        setContent {
            MobileTheme {
                Box(Modifier.size(640.dp, 360.dp).background(Color(0xFF202830))) {
                    SkipSegmentButton(
                        SkipPrompt(SkipSegment(SkipSegment.Kind.OPENING, 28.7, 118.7)),
                        { skipped++ },
                        Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        hint = "S",
                    )
                }
            }
        }
        onNodeWithText("Skip opening").assertExists()
        onNodeWithTag("skip_segment").assertTextContains("S", substring = true)
        screenshot()
        onNodeWithTag("skip_segment").performClick()
        assertEquals(1, skipped)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.screenshot() {
        val dir = System.getProperty("yarmiplaytv.screenshotDir") ?: return
        val file = File(dir, "${System.getProperty("os.name").substringBefore(' ').lowercase()}/skip_button.png")
        file.parentFile.mkdirs()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }
}
