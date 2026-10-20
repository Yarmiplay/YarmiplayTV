package com.yarmiplaytv.playback

import com.yarmiplaytv.ui.mobile.DesktopPlayerInput

internal actual fun platformAdjustVolume(delta: Double) = DesktopPlayerInput.changeVolume(delta)

internal actual fun platformToggleMute() {
    val volume = DesktopPlayerInput.volume ?: return
    val mute = !volume.muted.value
    volume.setMuted(mute)
    DesktopPlayerInput.announce(if (mute) "Muted" else "Volume ${volume.volume.value.toInt()}%")
}
