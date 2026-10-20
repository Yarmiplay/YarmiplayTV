package com.yarmiplaytv.playback

import android.media.AudioManager
import com.yarmiplaytv.DevicePlatform
import kotlin.math.roundToInt

internal actual fun platformAdjustVolume(delta: Double) {
    val audio = audio() ?: return
    val steps = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    val next = (current + delta / 100.0 * steps).roundToInt().coerceIn(0, steps)
    if (next > 0 && audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
    }
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
}

internal actual fun platformToggleMute() {
    val audio = audio() ?: return
    val mute = !audio.isStreamMute(AudioManager.STREAM_MUSIC)
    audio.adjustStreamVolume(
        AudioManager.STREAM_MUSIC,
        if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
        0,
    )
}

private fun audio(): AudioManager? {
    val context = DevicePlatform.appContextOrNull() ?: return null
    val audio = context.getSystemService(AudioManager::class.java) ?: return null
    return if (audio.isVolumeFixed) null else audio
}
