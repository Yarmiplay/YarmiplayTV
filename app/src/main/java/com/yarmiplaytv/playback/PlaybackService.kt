package com.yarmiplaytv.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yarmiplaytv.MainActivity
import com.yarmiplaytv.R
import com.yarmiplaytv.YarmiplayTvApp
import com.yarmiplaytv.media.FileNames
import com.yarmiplaytv.player.PlaybackState
import com.yarmiplaytv.sync.NowPlaying
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.RoomState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private val container get() = (application as YarmiplayTvApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        startInForeground()
        observePlayback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PLAY_PAUSE -> {
                container.player.setPaused(!container.player.isPaused)
            }
            ACTION_PREVIOUS -> {
                container.playlist.skip(forward = false)
            }
            ACTION_NEXT -> {
                container.playlist.skip(forward = true)
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
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
                description = "Shows playback controls and keeps room synchronization active"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun startInForeground() {
        val notification = buildNotification(
            container.player.state.value,
            container.sync.room.value,
            container.playlist.nowPlaying.value,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
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
            ) { settings, playerState, room, nowPlaying ->
                StateUpdate(settings.playback.stayConnectedMinimized, playerState, room, nowPlaying)
            }.collect { update ->
                val inRoom = update.room.status == ConnectionStatus.CONNECTED
                val isLoaded = update.playerState.fileLoaded
                if (!update.stayConnected || (!isLoaded && !inRoom)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                runCatching {
                    if (wakeLock?.isHeld == false) {
                        wakeLock?.acquire(10 * 60 * 1000L)
                    }
                }
                val notification = buildNotification(update.playerState, update.room, update.nowPlaying)
                NotificationManagerCompat.from(this@PlaybackService).notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(
        playerState: PlaybackState,
        room: RoomState,
        nowPlaying: NowPlaying?,
    ): Notification {
        val inRoom = room.status == ConnectionStatus.CONNECTED
        val isAudio = FileNames.isAudio(nowPlaying?.fileName ?: playerState.url.orEmpty())
        val title = nowPlaying?.title
            ?: FileNames.baseName(playerState.url.orEmpty()).ifEmpty {
                if (inRoom) "Room: ${room.room}" else "YarmiplayTV"
            }
        val series = nowPlaying?.episode?.seriesName
        val roomInfo = if (inRoom) "Room: ${room.room} (${room.users.size} viewers)" else null
        val subtitle = listOfNotNull(series, roomInfo).joinToString(" · ").ifEmpty {
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

        val togglePlayIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE_PLAY_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val playPauseIcon = if (playerState.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val playPauseLabel = if (playerState.paused) "Play" else "Pause"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(playPauseIcon, playPauseLabel, togglePlayIntent)

        val skips = inRoom && room.playlist.isNotEmpty()
        if (skips) {
            val prevIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, PlaybackService::class.java).setAction(ACTION_PREVIOUS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val nextIntent = PendingIntent.getService(
                this,
                3,
                Intent(this, PlaybackService::class.java).setAction(ACTION_NEXT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            builder.addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
        }

        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        scope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "yarmiplay_playback"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.yarmiplaytv.action.PLAYBACK_START"
        const val ACTION_STOP = "com.yarmiplaytv.action.PLAYBACK_STOP"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.yarmiplaytv.action.PLAYBACK_PLAY_PAUSE"
        const val ACTION_PREVIOUS = "com.yarmiplaytv.action.PLAYBACK_PREVIOUS"
        const val ACTION_NEXT = "com.yarmiplaytv.action.PLAYBACK_NEXT"

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
    )
}
