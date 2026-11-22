package com.yarmiplaytv.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.player.Player
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [PlaybackState] with its clock ([PlaybackState.position]) left out. The clock moves four times a second while
 * playing; screens show it from [Player.currentPosition] in their own small composables instead, so a screen that
 * follows this state is rebuilt only when something it shows changes.
 */
fun PlaybackState.withoutClock(): PlaybackState = if (position == 0.0) this else copy(position = 0.0)

/** [Player.state] without its clock: emits only when something other than the position changed. */
fun Player.stateWithoutClock(): Flow<PlaybackState> = state.map { it.withoutClock() }.distinctUntilChanged()

/** [Player.state] without its clock, collected while the screen is started (see [withoutClock]). */
@Composable
fun Player.collectStateWithoutClock(): State<PlaybackState> {
    val flow = remember(this) { stateWithoutClock() }
    return flow.collectAsStateWithLifecycle(state.value.withoutClock())
}

/** Like [collectStateWithoutClock], collected whatever the lifecycle (for picture-in-picture, which runs stopped). */
@Composable
fun Player.collectStateWithoutClockAlways(): State<PlaybackState> {
    val flow = remember(this) { stateWithoutClock() }
    return flow.collectAsState(state.value.withoutClock())
}
