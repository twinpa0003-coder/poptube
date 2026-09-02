package com.jklee.poptube

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 재생 중에만 살아 있는 포그라운드 서비스.
 *
 * 이게 있어야 화면을 끄거나 앱을 백그라운드로 보냈을 때 시스템이 프로세스를 죽이지 않는다.
 * 소리를 내는 주체는 WebView이고, 이 서비스는 "프로세스를 살려두는 역할 + 알림 컨트롤"만 한다.
 */
class PlaybackService : Service() {

    /** 재생 중에만 잡는다. 화면이 꺼진 뒤 CPU 가 잠들어 오디오가 끊기는 걸 막는다. */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> PlaybackBus.dispatch(PlaybackBus.Command.TOGGLE)
            ACTION_STOP -> {
                PlaybackBus.dispatch(PlaybackBus.Command.PAUSE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val playing = intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.notif_title)
        startForegroundCompat(buildNotification(title, playing))
        updateWakeLock(playing)
        return START_NOT_STICKY
    }

    private fun updateWakeLock(playing: Boolean) {
        if (playing) {
            if (wakeLock == null) {
                val pm = getSystemService(android.os.PowerManager::class.java)
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "PopTube:playback")
                    .apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire(6 * 60 * 60 * 1000L)
        } else {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(title: String, playing: Boolean): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, PopTubeApp.CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (playing) R.string.action_pause else R.string.action_play),
                servicePendingIntent(ACTION_TOGGLE, 1)
            )
            .addAction(
                R.drawable.ic_close,
                getString(R.string.action_stop),
                servicePendingIntent(ACTION_STOP, 2)
            )
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1))
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    override fun onDestroy() {
        updateWakeLock(false)
        NotificationManagerCompat.from(this).cancel(NOTIF_ID)
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1001
        const val ACTION_UPDATE = "com.jklee.poptube.UPDATE"
        const val ACTION_TOGGLE = "com.jklee.poptube.TOGGLE"
        const val ACTION_STOP = "com.jklee.poptube.STOP"
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_TITLE = "title"

        fun update(context: Context, playing: Boolean, title: String) {
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_PLAYING, playing)
                .putExtra(EXTRA_TITLE, title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}

/** 알림 버튼 → 액티비티의 WebView 로 명령을 전달하는 초경량 버스. */
object PlaybackBus {
    enum class Command { TOGGLE, PAUSE }

    @Volatile
    var listener: ((Command) -> Unit)? = null

    fun dispatch(command: Command) {
        listener?.invoke(command)
    }
}
