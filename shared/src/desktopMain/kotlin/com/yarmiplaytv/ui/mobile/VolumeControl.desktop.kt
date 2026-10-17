package com.yarmiplaytv.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun PlayerVolumeControl(onInteract: () -> Unit, modifier: Modifier, onVideo: Boolean) {
    val control = DesktopPlayerInput.volume ?: return
    PlayerVolumeBar(control, onInteract, modifier, muteShortcut = "M", onVideo = onVideo)
}
