package com.yarmiplaytv.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as SessionPlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.MainActivity
import com.yarmiplaytv.YarmiplayTvApp
import com.yarmiplaytv.data.VoiceTalkMode
import com.yarmiplaytv.media.FileNames
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.sync.NowPlaying
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.voice.VoiceConnection
import com.yarmiplaytv.voice.VoiceUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps playback (and on phone/tablet, room voice) alive in the background with a media notification.
 * Voice actions appear only on phone/tablet while voice is active — not on TV.
 */
class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null
    private val container get() = (application as YarmiplayTvApp).container
    private val isPhoneOrTablet get() =
        container.deviceKind == DeviceKind.PHONE || container.deviceKind == DeviceKind.TABLET

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
        acquireWakeLock()
        startInForeground()
        observePlayback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startInForeground()
            ACTION_TOGGLE_PLAY_PAUSE -> handleTogglePlayPause()
            ACTION_PREVIOUS -> handlePrevious()
            ACTION_NEXT -> handleNext()
            ACTION_VOICE_MUTE -> if (isPhoneOrTablet) container.voice.toggleSelfMuted()
            ACTION_VOICE_TALK -> if (isPhoneOrTablet) handleVoiceTalk()
            ACTION_VOICE_LEAVE -> if (isPhoneOrTablet) container.voice.leave()
            ACTION_VOICE_JOIN -> if (isPhoneOrTablet) container.voice.join()
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EXIT -> {
                handleExit()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun handleVoiceTalk() {
        val ui = container.voice.ui.value
        when (ui.talkMode) {
            VoiceTalkMode.TOGGLE -> container.voice.toggleTalk()
            VoiceTalkMode.PUSH_TO_TALK -> container.voice.setPushToTalkHeld(!ui.talking)
            VoiceTalkMode.OPEN_MIC -> container.voice.toggleSelfMuted()
        }
    }

    private fun initMediaSession() {
        mediaSession = MediaSession(this, "YarmiplayTV").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    handleTogglePlayPause()
                }

                override fun onPause() {
                    handleTogglePlayPause()
                }

                override fun onSkipToPrevious() {
                    handlePrevious()
                }

                override fun onSkipToNext() {
                    handleNext()
                }

                override fun onSeekTo(pos: Long) {
                    val targetSeconds = (pos / 1000.0).coerceAtLeast(0.0)
                    container.player.seek(targetSeconds)
                    updatePlaybackState(container.player.state.value, targetPosition = targetSeconds)
                }

                override fun onStop() {
                    handleExit()
                }
            })
            isActive = true
        }
    }

    private fun handleTogglePlayPause() {
        container.player.setPaused(!container.player.isPaused)
    }

    private fun handlePrevious() {
        val inRoom = container.sync.room.value.status == ConnectionStatus.CONNECTED
        if (inRoom && container.playlist.skipTarget(container.sync.room.value, forward = false) != null) {
            container.playlist.skip(forward = false)
        } else if (container.player.currentPosition() > 3.0) {
            container.player.seek(0.0)
            updatePlaybackState(container.player.state.value, targetPosition = 0.0)
        } else {
            container.player.seekRelative(-10.0)
            updatePlaybackState(container.player.state.value, targetPosition = (container.player.currentPosition() - 10.0).coerceAtLeast(0.0))
        }
    }

    private fun handleNext() {
        val inRoom = container.sync.room.value.status == ConnectionStatus.CONNECTED
        if (inRoom && container.playlist.skipTarget(container.sync.room.value, forward = true) != null) {
            container.playlist.skip(forward = true)
        } else {
            container.player.seekRelative(10.0)
            val dur = container.player.state.value.duration
            val target = (container.player.currentPosition() + 10.0).let { if (dur > 0) it.coerceAtMost(dur) else it }
            updatePlaybackState(container.player.state.value, targetPosition = target)
        }
    }

    private fun handleExit() {
        MainActivity.isExiting = true
        container.playlist.stop()
        container.sync.disconnect()
        releaseWakeLock()
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        MainActivity.exit(this)
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YarmiplayTV:PlaybackWakeLock").apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback & Room Sync",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows playback and voice controls and keeps room synchronization active"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun hasRecordAudio(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Only claim the microphone FGS type when RECORD_AUDIO is granted. Since 2.7.7 we request
     * that permission on Mic tap (not at launch); auto-join can be CONNECTED without it, and
     * [startForeground] with [ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE] then throws
     * SecurityException and the whole notification disappears.
     */
    private fun foregroundTypes(voice: VoiceUiState): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (isPhoneOrTablet &&
            voice.showUi &&
            voice.connection == VoiceConnection.CONNECTED &&
            hasRecordAudio()
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    private fun startInForeground() {
        val playerState = container.player.state.value
        val room = container.sync.room.value
        val nowPlaying = container.playlist.nowPlaying.value
        val voice = container.voice.ui.value
        updateSession(playerState, room, nowPlaying, voice)
        val notification = buildNotification(playerState, room, nowPlaying, voice)
        promoteForeground(notification, voice)
    }

    private fun promoteForeground(notification: Notification, voice: VoiceUiState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val types = foregroundTypes(voice)
            try {
                startForeground(NOTIFICATION_ID, notification, types)
            } catch (_: SecurityException) {
                // Mic type without RECORD_AUDIO (or OEM quirk): keep media playback notification.
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observePlayback() {
        scope.launch {
            combine(
                container.settings,
                container.player.state,
                container.sync.room,
                container.playlist.nowPlaying,
                container.voice.ui,
            ) { settings, playerState, room, nowPlaying, voice ->
                StateUpdate(settings.playback.stayConnectedMinimized, playerState, room, nowPlaying, voice)
            }.collect { update ->
                val inRoom = update.room.status == ConnectionStatus.CONNECTED
                val isLoaded = update.playerState.fileLoaded
                val voiceKeepsAlive = isPhoneOrTablet &&
                    update.voice.showUi &&
                    update.voice.connection == VoiceConnection.CONNECTED
                if (!update.stayConnected && !voiceKeepsAlive) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                if (update.stayConnected && !isLoaded && !inRoom && !voiceKeepsAlive) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                runCatching {
                    if (wakeLock?.isHeld == false) {
                        wakeLock?.acquire(10 * 60 * 1000L)
                    }
                }
                updateSession(update.playerState, update.room, update.nowPlaying, update.voice)
                val notification = buildNotification(update.playerState, update.room, update.nowPlaying, update.voice)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    promoteForeground(notification, update.voice)
                } else {
                    NotificationManagerCompat.from(this@PlaybackService).notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun updateSession(playerState: PlaybackState, room: RoomState, nowPlaying: NowPlaying?, voice: VoiceUiState) {
        val inRoom = room.status == ConnectionStatus.CONNECTED
        val isAudio = FileNames.isAudio(nowPlaying?.fileName ?: playerState.url.orEmpty())
        val title = nowPlaying?.title
            ?: FileNames.baseName(playerState.url.orEmpty()).ifEmpty {
                if (inRoom) "Room: ${room.room}" else "YarmiplayTV"
            }
        val series = nowPlaying?.episode?.seriesName
        val roomInfo = if (inRoom) "Room: ${room.room} (${room.users.size} viewers)" else null
        val voiceInfo = voiceSubtitle(voice)
        val subtitle = listOfNotNull(series, roomInfo, voiceInfo).joinToString(" · ").ifEmpty {
            if (isAudio) "Music" else "Playback active"
        }

        val durationMs = (playerState.duration * 1000).toLong().coerceAtLeast(0L)
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, subtitle)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
            .build()
        mediaSession?.setMetadata(metadata)

        updatePlaybackState(playerState)
    }

    private fun voiceSubtitle(voice: VoiceUiState): String? {
        if (!isPhoneOrTablet || !voice.showUi) return null
        return when {
            voice.connection == VoiceConnection.CONNECTING -> "Voice connecting"
            voice.connection == VoiceConnection.FAILED -> "Voice failed"
            voice.listenOnly -> "Voice: listening only"
            voice.micLive || (voice.talkMode == VoiceTalkMode.OPEN_MIC && !voice.selfMuted && voice.connection == VoiceConnection.CONNECTED) ->
                "Voice: mic on"
            voice.connection == VoiceConnection.CONNECTED -> "Voice connected"
            else -> null
        }
    }

    private fun updatePlaybackState(playerState: PlaybackState, targetPosition: Double? = null) {
        val posSeconds = targetPosition ?: container.player.currentPosition()
        val posMs = (posSeconds * 1000).toLong().coerceAtLeast(0L)
        val isPaused = playerState.paused
        val state = if (isPaused) SessionPlaybackState.STATE_PAUSED else SessionPlaybackState.STATE_PLAYING
        val speed = if (isPaused) 0f else (playerState.speed.toFloat().takeIf { it > 0f } ?: 1f)

        val actions = SessionPlaybackState.ACTION_PLAY or
            SessionPlaybackState.ACTION_PAUSE or
            SessionPlaybackState.ACTION_PLAY_PAUSE or
            SessionPlaybackState.ACTION_STOP or
            SessionPlaybackState.ACTION_SKIP_TO_PREVIOUS or
            SessionPlaybackState.ACTION_SKIP_TO_NEXT or
            SessionPlaybackState.ACTION_SEEK_TO

        val playbackState = SessionPlaybackState.Builder()
            .setActions(actions)
            .setState(state, posMs, speed, SystemClock.elapsedRealtime())
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    private fun buildNotification(
        playerState: PlaybackState,
        room: RoomState,
        nowPlaying: NowPlaying?,
        voice: VoiceUiState,
    ): Notification {
        val inRoom = room.status == ConnectionStatus.CONNECTED
        val isAudio = FileNames.isAudio(nowPlaying?.fileName ?: playerState.url.orEmpty())
        val title = nowPlaying?.title
            ?: FileNames.baseName(playerState.url.orEmpty()).ifEmpty {
                if (inRoom) "Room: ${room.room}" else "YarmiplayTV"
            }
        val series = nowPlaying?.episode?.seriesName
        val roomInfo = if (inRoom) "Room: ${room.room} (${room.users.size} viewers)" else null
        val voiceInfo = voiceSubtitle(voice)
        val subtitle = listOfNotNull(series, roomInfo, voiceInfo).joinToString(" · ").ifEmpty {
            if (isAudio) "Music" else "Playback active"
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun servicePending(requestCode: Int, action: String) = PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val prevIntent = servicePending(2, ACTION_PREVIOUS)
        val togglePlayIntent = servicePending(1, ACTION_TOGGLE_PLAY_PAUSE)
        val nextIntent = servicePending(3, ACTION_NEXT)
        val exitIntent = servicePending(4, ACTION_EXIT)

        val playPauseIcon = if (playerState.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val playPauseLabel = if (playerState.paused) "Play" else "Pause"

        val showVoiceConnected = isPhoneOrTablet && voice.showUi && voice.connection == VoiceConnection.CONNECTED
        val showVoiceMembership = isPhoneOrTablet && voice.showVoiceMembershipButton
        val mediaStyle = Notification.MediaStyle()
        mediaSession?.let { mediaStyle.setMediaSession(it.sessionToken) }
        mediaStyle.setShowActionsInCompactView(0, 1, 2)

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setOngoing(!playerState.paused || showVoiceConnected || showVoiceMembership)
            .setDeleteIntent(exitIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setStyle(mediaStyle)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                    "Previous",
                    prevIntent,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, playPauseIcon),
                    playPauseLabel,
                    togglePlayIntent,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_next),
                    "Next",
                    nextIntent,
                ).build(),
            )

        if (showVoiceConnected && voice.showMicButton) {
            val micAction = when (voice.talkMode) {
                VoiceTalkMode.OPEN_MIC ->
                    (if (voice.selfMuted) "Unmute" else "Mute") to ACTION_VOICE_MUTE
                VoiceTalkMode.TOGGLE, VoiceTalkMode.PUSH_TO_TALK ->
                    (if (voice.micLive || voice.talking) "Mic off" else "Mic on") to ACTION_VOICE_TALK
            }
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now),
                    micAction.first,
                    servicePending(5, micAction.second),
                ).build(),
            )
        }
        if (showVoiceMembership) {
            if (voice.voiceMembershipIsLeave) {
                builder.addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        "Leave voice",
                        servicePending(7, ACTION_VOICE_LEAVE),
                    ).build(),
                )
            } else {
                builder.addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now),
                        "Join voice",
                        servicePending(8, ACTION_VOICE_JOIN),
                    ).build(),
                )
            }
        }

        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Exit",
                exitIntent,
            ).build(),
        )
        return builder.build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val voiceKeepsAlive = isPhoneOrTablet &&
            container.voice.ui.value.showUi &&
            container.voice.ui.value.connection == VoiceConnection.CONNECTED
        if (!container.settings.value.playback.stayConnectedMinimized && !voiceKeepsAlive) {
            handleExit()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        scope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "yarmiplay_playback"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.yarmiplaytv.action.PLAYBACK_START"
        const val ACTION_STOP = "com.yarmiplaytv.action.PLAYBACK_STOP"
        const val ACTION_EXIT = "com.yarmiplaytv.action.PLAYBACK_EXIT"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.yarmiplaytv.action.PLAYBACK_PLAY_PAUSE"
        const val ACTION_PREVIOUS = "com.yarmiplaytv.action.PLAYBACK_PREVIOUS"
        const val ACTION_NEXT = "com.yarmiplaytv.action.PLAYBACK_NEXT"
        const val ACTION_VOICE_MUTE = "com.yarmiplaytv.action.VOICE_MUTE"
        const val ACTION_VOICE_TALK = "com.yarmiplaytv.action.VOICE_TALK"
        const val ACTION_VOICE_LEAVE = "com.yarmiplaytv.action.VOICE_LEAVE"
        const val ACTION_VOICE_JOIN = "com.yarmiplaytv.action.VOICE_JOIN"

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).setAction(ACTION_STOP)
            context.stopService(intent)
        }
    }

    private data class StateUpdate(
        val stayConnected: Boolean,
        val playerState: PlaybackState,
        val room: RoomState,
        val nowPlaying: NowPlaying?,
        val voice: VoiceUiState,
    )
}
