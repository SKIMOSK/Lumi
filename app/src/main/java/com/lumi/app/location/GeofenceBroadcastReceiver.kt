package com.lumi.app.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "lumi_geofence"
        private var notifId = 5000
    }

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val reminderText = intent.getStringExtra(GeofenceHelper.EXTRA_REMINDER_TEXT)
            ?: "Reminder locație Lumi"
        val locationName = intent.getStringExtra(GeofenceHelper.EXTRA_LOCATION_NAME) ?: ""
        val trigger = intent.getStringExtra(GeofenceHelper.EXTRA_TRIGGER) ?: "enter"

        val prefix = if (trigger == "exit") "Ai plecat din $locationName:" else "Ești la $locationName:"
        val fullText = "$prefix $reminderText"

        showNotification(context, fullText)
        sendToTts(context, fullText)
    }

    private fun showNotification(context: Context, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lumi Remindere Locație",
                    NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Lumi Reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(notifId++, notification)
    }

    private fun sendToTts(context: Context, text: String) {
        context.sendBroadcast(Intent("com.lumi.app.TTS_SPEAK").apply {
            putExtra("text", text)
        })
    }
}
