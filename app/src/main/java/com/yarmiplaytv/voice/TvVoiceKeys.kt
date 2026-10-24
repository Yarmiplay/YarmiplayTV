package com.yarmiplaytv.voice

import android.view.KeyEvent

/**
 * TV remote keys that toggle the room mic (tap on → tap off). Never hold-to-talk.
 *
 * ## Keys handled (for real-TV testing)
 *
 * When the app receives them (some remotes send the mic key to Google Assistant instead):
 * - [KeyEvent.KEYCODE_SEARCH] (84) — common mic / Google Assistant button
 * - [KeyEvent.KEYCODE_VOICE_ASSIST] (231) — voice-assist key
 * - [KeyEvent.KEYCODE_ASSIST] (219) — assist key on some remotes
 * - [KeyEvent.KEYCODE_MEDIA_RECORD] (130) — record key on a few remotes
 *
 * Always available (every remote):
 * - Focusable **Mic** button in the player controls and room panel — press OK to toggle
 *
 * Shortcut only (not the main path):
 * - Long-press OK / Center / Enter (≥ [OK_LONG_PRESS_MS]) while player controls are hidden
 *
 * Not used for talk: D-pad, Menu, Back, media transport keys.
 */
object TvVoiceKeys {
    const val OK_LONG_PRESS_MS = 500L

    fun isVoiceToggleKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_SEARCH,
        KeyEvent.KEYCODE_VOICE_ASSIST,
        KeyEvent.KEYCODE_ASSIST,
        KeyEvent.KEYCODE_MEDIA_RECORD,
        -> true
        else -> false
    }

    fun isOkKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        -> true
        else -> false
    }

    /** Friendly label for the one-time hint / saved remote key. */
    fun labelFor(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_SEARCH -> "mic / search button"
        KeyEvent.KEYCODE_VOICE_ASSIST -> "voice-assist button"
        KeyEvent.KEYCODE_ASSIST -> "assist button"
        KeyEvent.KEYCODE_MEDIA_RECORD -> "record button"
        else -> "mic button"
    }

    fun hintText(remoteKeyLabel: String): String {
        val key = remoteKeyLabel.ifBlank { "mic / voice key" }
        return "Tap Mic to talk. Your remote’s $key also toggles the mic (if the TV sends it to the app). Long-press OK works as a shortcut."
    }
}
