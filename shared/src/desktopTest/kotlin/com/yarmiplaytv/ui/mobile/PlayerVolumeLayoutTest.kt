package com.yarmiplaytv.ui.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerVolumeLayoutTest {
    @Test
    fun `the percent keeps its width and the slider uses what is left up to its cap`() {
        // px stand-ins at density 1: mute button 48, slider cap 120, "100%" about 40, "Muted" about 48.
        assertEquals(120, volumeSliderWidth(maxWidth = 400, muteWidth = 48, labelWidth = 40, sliderMax = 120))
        assertEquals(120, volumeSliderWidth(maxWidth = 48 + 40 + 120, muteWidth = 48, labelWidth = 40, sliderMax = 120))
        // Narrow portrait row once save, screenshot and menu have taken their 48dp buttons.
        assertEquals(52, volumeSliderWidth(maxWidth = 140, muteWidth = 48, labelWidth = 40, sliderMax = 120))
        assertEquals(44, volumeSliderWidth(maxWidth = 140, muteWidth = 48, labelWidth = 48, sliderMax = 120))
        // Not enough room for a track: drop the slider rather than wrap the percent.
        assertEquals(0, volumeSliderWidth(maxWidth = 80, muteWidth = 48, labelWidth = 40, sliderMax = 120))
        assertEquals(120, volumeSliderWidth(maxWidth = Int.MAX_VALUE, muteWidth = 48, labelWidth = 36, sliderMax = 120))
    }
}
