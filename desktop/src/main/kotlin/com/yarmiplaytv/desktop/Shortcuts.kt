package com.yarmiplaytv.desktop

import androidx.compose.ui.input.key.Key
import com.yarmiplaytv.ui.mobile.PlayerInput
import com.yarmiplaytv.ui.mobile.VOLUME_STEP

/** What the window's keyboard shortcuts act on. */
internal interface ShortcutTarget {
    val isFullscreen: Boolean
    fun setFullscreen(on: Boolean)
    /** Goes back one screen (or closes a sheet); false when there's nowhere to go back to. */
    fun back(): Boolean
    /** The player screen's input while it's showing. */
    val player: PlayerInput?
    fun changeVolume(delta: Double)
    fun toggleMute()
    /** Opens the chat panel with the cursor in the message box. */
    fun focusChat()
    /** Saves the current frame to the Pictures folder. */
    fun screenshot()
    /** Goes into the mini player, or back to the full window. */
    fun togglePictureInPicture()
}

/**
 * Window-level shortcuts. The window only passes keys nothing focused has used, and the side panel keeps its
 * keys to itself, so typing in a text field never triggers them. Esc leaves full screen, else goes back; the
 * rest only work on the player screen, and all but H (which hides or shows the controls), F5 (screenshot) and
 * P (mini player) show the controls. N and B (or the media keys) go to the room playlist's next and previous entry. In the mini player, Esc's back goes to the full window (the player's back handler).
 */
internal fun handleShortcut(key: Key, modified: Boolean, target: ShortcutTarget): Boolean {
    if (key == Key.Escape) {
        if (target.isFullscreen) {
            target.setFullscreen(false)
            return true
        }
        return target.back()
    }
    val input = target.player ?: return false
    if (modified) return false
    if (key == Key.H) {
        input.toggleControls()
        return true
    }
    if (key == Key.F5) {
        target.screenshot()
        return true
    }
    if (key == Key.P) {
        target.togglePictureInPicture()
        return true
    }
    when (key) {
        Key.Spacebar, Key.K -> input.togglePause()
        Key.DirectionLeft, Key.J -> input.seekBy(-input.seekStep)
        Key.DirectionRight, Key.L -> input.seekBy(input.seekStep)
        Key.DirectionUp -> target.changeVolume(VOLUME_STEP)
        Key.DirectionDown -> target.changeVolume(-VOLUME_STEP)
        Key.M -> target.toggleMute()
        Key.F, Key.F11 -> target.setFullscreen(!target.isFullscreen)
        Key.Enter, Key.NumPadEnter -> target.focusChat()
        Key.N, Key.MediaNext -> input.skip(true)
        Key.B, Key.MediaPrevious -> input.skip(false)
        else -> return false
    }
    input.showControls()
    return true
}
