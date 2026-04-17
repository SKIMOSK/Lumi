package com.lumi.app.bluetooth

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lumi.app.LumiApplication
import com.lumi.app.MainActivity
import com.lumi.app.R

/**
 * Foreground service that keeps the BLE connection alive when the app is backgrounded.
 */
class LumiBluetoothService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): LumiBluetoothService = this@LumiBluetoothService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, LumiApplication.CHANNEL_BT)
            .setContentTitle("Lumi conectat")
            .setContentText("Dispozitivul Lumi este activ în fundal.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1001
    }
}
