package com.digisafe.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class DigiSafeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_desc)
                enableVibration(true)
                setShowBadge(true)
            }

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "DigiSafe Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical scam detection alerts"
                enableVibration(true)
                setShowBadge(true)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    companion object {
        const val CHANNEL_ID = "digisafe_protection"
        const val ALERT_CHANNEL_ID = "digisafe_alerts"
        lateinit var instance: DigiSafeApp
            private set
    }
}
