package com.yarmiplaytv.ui.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** The device's media volume, which the volume buttons change too. Not on devices with a fixed volume. */
@Composable
actual fun PlayerVolumeControl(onInteract: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val control = remember(context) { MediaVolume.createOrNull(context) } ?: return
    DisposableEffect(control) {
        control.listen()
        onDispose { control.stopListening() }
    }
    PlayerVolumeBar(control, onInteract, modifier)
}

/** [AudioManager.STREAM_MUSIC] in percent of its steps; follows changes from the volume buttons while listening. */
internal class MediaVolume private constructor(private val context: Context, private val audio: AudioManager) : PlayerVolume {
    private val steps = audio.getStreamMaxVolume(STREAM).coerceAtLeast(1)
    private val _volume = MutableStateFlow(0.0)
    private val _muted = MutableStateFlow(false)
    override val volume: StateFlow<Double> = _volume.asStateFlow()
    override val muted: StateFlow<Boolean> = _muted.asStateFlow()
    override val max = 100.0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = read()
    }

    init {
        read()
    }

    override fun setVolume(percent: Double) {
        val step = (percent.coerceIn(0.0, max) / max * steps).roundToInt()
        if (step > 0 && audio.isStreamMute(STREAM)) audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0)
        // No flags: the player shows the volume itself, so the system's volume panel stays away.
        audio.setStreamVolume(STREAM, step, 0)
        read()
    }

    override fun setMuted(muted: Boolean) {
        audio.adjustStreamVolume(STREAM, if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        read()
    }

    fun listen() {
        read()
        // Sent by the system whenever a stream's volume or mute state changes, the volume buttons included.
        val filter = IntentFilter(VOLUME_CHANGED).apply { addAction(MUTE_CHANGED) }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stopListening() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun read() {
        _volume.value = audio.getStreamVolume(STREAM) * max / steps
        _muted.value = audio.isStreamMute(STREAM)
    }

    companion object {
        private const val STREAM = AudioManager.STREAM_MUSIC
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"

        fun createOrNull(context: Context): MediaVolume? {
            val audio = context.getSystemService(AudioManager::class.java) ?: return null
            return if (audio.isVolumeFixed) null else MediaVolume(context.applicationContext, audio)
        }
    }
}
