package com.yarmiplaytv.voice

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvVoiceKeysTest {
    @Test
    fun `voice toggle keys`() {
        assertTrue(TvVoiceKeys.isVoiceToggleKey(KeyEvent.KEYCODE_SEARCH))
        assertTrue(TvVoiceKeys.isVoiceToggleKey(KeyEvent.KEYCODE_VOICE_ASSIST))
        assertTrue(TvVoiceKeys.isVoiceToggleKey(KeyEvent.KEYCODE_ASSIST))
        assertTrue(TvVoiceKeys.isVoiceToggleKey(KeyEvent.KEYCODE_MEDIA_RECORD))
        assertFalse(TvVoiceKeys.isVoiceToggleKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(TvVoiceKeys.isVoiceToggleKey(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun `ok keys`() {
        assertTrue(TvVoiceKeys.isOkKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(TvVoiceKeys.isOkKey(KeyEvent.KEYCODE_ENTER))
        assertFalse(TvVoiceKeys.isOkKey(KeyEvent.KEYCODE_SEARCH))
    }

    @Test
    fun `hint mentions mic button`() {
        val text = TvVoiceKeys.hintText("mic / search button")
        assertTrue(text.contains("Mic"))
        assertTrue(text.contains("mic / search button"))
        assertEquals("mic / search button", TvVoiceKeys.labelFor(KeyEvent.KEYCODE_SEARCH))
    }
}
