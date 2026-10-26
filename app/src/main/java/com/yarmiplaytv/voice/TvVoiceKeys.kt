package com.yarmiplaytv.voice

import android.view.KeyEvent

/**
 * TV remote mic keys. Many remotes only stream audio while the mic/search key is held
 * (SEARCH / VOICE_ASSIST), so those keys are push-to-talk (down = talk, up = stop).
 * The on-screen Mic button stays tap-to-toggle for remotes that never deliver these keys.
 *
 * ## Keys handled (for real-TV testing)
 *
 * When the app receives them (some remotes send the mic key to Google Assistant instead):
 * - [KeyEvent.KEYCODE_SEARCH] (84) — common mic / Google Assistant button (hold-to-talk)
 * - [KeyEvent.KEYCODE_VOICE_ASSIST] (231) — voice-assist key (hold-to-talk)
 * - [KeyEvent.KEYCODE_ASSIST] (219) — assist key on some remotes (hold-to-talk)
 * - [KeyEvent.KEYCODE_MEDIA_RECORD] (130) — record key on a few remotes (hold-to-talk)
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

    /** Remote mic / search keys that should hold-to-talk (hardware often only captures while held). */
    fun isVoiceHoldKey(keyCode: Int): Boolean = isVoiceToggleKey(keyCode)

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
        return "Tap Mic to talk. Hold your remote’s $key to talk (if the TV sends it to the app). Long-press OK works as a shortcut."
    }
}
