package com.syncplaytv.sync

import com.syncplaytv.player.Player
import com.syncplaytv.syncplay.PlayerAdapter

/** Exposes the mpv [Player] to the Syncplay engine; reads are served from the player's caches. */
class MpvPlayerAdapter(private val player: Player) : PlayerAdapter {
    override val isFileLoaded: Boolean get() = player.isFileLoaded
    override val duration: Double get() = player.duration
    override val position: Double get() = player.currentPosition()
    override val isPaused: Boolean get() = player.isPaused

    override fun setPaused(paused: Boolean) = player.setPaused(paused)
    override fun seek(position: Double) = player.seek(position)
    override fun setSpeed(speed: Double) = player.setSpeed(speed)
}
