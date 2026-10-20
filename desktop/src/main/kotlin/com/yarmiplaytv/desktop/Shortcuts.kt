package com.yarmiplaytv.desktop

import androidx.compose.ui.input.key.Key
import com.yarmiplaytv.playback.PlaybackAction
import com.yarmiplaytv.playback.playbackAction
import com.yarmiplaytv.ui.mobile.PlayerInput

/** What the window's keyboard shortcuts act on. */
internal interface ShortcutTarget {
    val isFullscreen: Boolean
    fun setFullscreen(on: Boolean)
    /** Goes back one screen (or closes a sheet); false when there's nowhere to go back to. */
    fun back(): Boolean
    /** The player screen's input while it's showing. */
    val player: PlayerInput?
    /** mpv playback actions, and the app's own keys that share that table. False when it did not apply. */
    fun perform(action: PlaybackAction): Boolean
}

/**
 * Window-level shortcuts. The window only passes keys nothing focused has used, and the side panel keeps its
 * keys to itself, so typing in a text field never triggers them. Esc leaves full screen, else goes back.
 * The rest follow mpv's defaults while the player is showing. Meta is left alone. Pause and other transport
 * keys show the controls; arrow-key seeks do not (so the HUD stays down). Picture and subtitle keys use the
 * on-screen display instead.
 */
internal fun handleShortcut(
    key: Key,
    ctrl: Boolean,
    alt: Boolean,
    shift: Boolean,
    meta: Boolean,
    target: ShortcutTarget,
): Boolean {
    if (key == Key.Escape && !ctrl && !alt && !meta && !shift) {
        if (target.isFullscreen) {
            target.setFullscreen(false)
            return true
        }
        return target.back()
    }
    if (meta) return false
    if (target.player == null) return false
    val action = playbackAction(key, shift = shift, ctrl = ctrl, alt = alt) ?: return false
    val handled = when (action) {
        PlaybackAction.Fullscreen -> {
            target.setFullscreen(!target.isFullscreen)
            true
        }
        PlaybackAction.ToggleControls -> {
            target.player?.toggleControls()
            true
        }
        PlaybackAction.SkipSegment -> target.perform(action)
        PlaybackAction.FocusChat -> target.perform(action)
        PlaybackAction.TogglePip -> target.perform(action)
        else -> target.perform(action)
    }
    if (handled && action.showsControls) target.player?.showControls()
    return handled
}
