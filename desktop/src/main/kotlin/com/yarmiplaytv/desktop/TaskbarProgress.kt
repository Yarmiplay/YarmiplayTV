package com.yarmiplaytv.desktop

import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.player.Player
import java.awt.Taskbar
import java.awt.Window
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * The window's taskbar button follows the video (Windows): green as far as it has played, yellow while paused, and
 * nothing when no video is open or it has no length (a live stream). Does nothing where the taskbar can't show it.
 */
internal suspend fun showPlaybackOnTaskbar(window: Window, player: Player) {
    val taskbar = runCatching { Taskbar.getTaskbar() }.getOrNull() ?: return
    if (!taskbar.isSupported(Taskbar.Feature.PROGRESS_VALUE_WINDOW) || !taskbar.isSupported(Taskbar.Feature.PROGRESS_STATE_WINDOW)) return
    try {
        player.state.map(::taskbarProgress).distinctUntilChanged().collect { progress ->
            if (progress == null) {
                taskbar.setWindowProgressState(window, Taskbar.State.OFF)
            } else {
                // Setting a value turns the bar on (green), so the paused state goes after it.
                taskbar.setWindowProgressValue(window, progress.percent)
                taskbar.setWindowProgressState(window, if (progress.paused) Taskbar.State.PAUSED else Taskbar.State.NORMAL)
            }
        }
    } finally {
        runCatching { taskbar.setWindowProgressState(window, Taskbar.State.OFF) }
    }
}

internal data class TaskbarProgress(val percent: Int, val paused: Boolean)

/** What the taskbar shows for [state]: null for nothing. */
internal fun taskbarProgress(state: PlaybackState): TaskbarProgress? {
    if (!state.fileLoaded || state.eofReached || state.duration <= 0.0) return null
    val percent = (state.position / state.duration * 100).roundToInt().coerceIn(0, 100)
    return TaskbarProgress(percent, state.paused)
}
