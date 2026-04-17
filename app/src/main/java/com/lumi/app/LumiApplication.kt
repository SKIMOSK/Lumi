package com.lumi.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class LumiApplication : Application() {

    companion object {
        const val CHANNEL_BT = "lumi_bluetooth"
        const val CHANNEL_AI = "lumi_ai"
        lateinit var instance: LumiApplication
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_BT, "Lumi Bluetooth", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Lumi device connection status" }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_AI, "Lumi AI", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Lumi AI responses" }
            )
        }
    }
}
