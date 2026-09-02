package com.jklee.poptube

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class PopTubeApp : Application() {

    override fun onCreate() {
        super.onCreate()
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
