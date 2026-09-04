package com.jklee.poptube

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class PopTubeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 액티비티가 뜨기 전에 죽는 경우까지 잡으려면 여기서 가장 먼저 걸어야 한다.
        DiagnosticLog.installCrashHandler(this)
        DiagnosticLog.i("app start")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_PLAYBACK,
                getString(R.string.channel_playback),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_PLAYBACK = "playback"
    }
}
