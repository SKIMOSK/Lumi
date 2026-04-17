package com.lumi.app.notifications

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CapturedNotification(
    val appName: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val key: String
) {
    fun formatted(): String {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        return "[$time] $appName — $title: $text"
    }
}

class LumiNotificationService : NotificationListenerService() {

    companion object {
        private val recentNotifications = ArrayDeque<CapturedNotification>(50)
        private val MAX_STORED = 50

        fun getRecent(limit: Int = 20): List<CapturedNotification> =
            recentNotifications.takeLast(limit)

        fun getFromApp(packageName: String, limit: Int = 10): List<CapturedNotification> =
            recentNotifications.filter { it.packageName == packageName }.takeLast(limit)

        fun formatSummary(limit: Int = 15): String {
            val notifications = getRecent(limit)
            if (notifications.isEmpty()) return "Nu există notificări recente."
            return notifications.joinToString("\n") { it.formatted() }
        }

        fun isEnabled(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            val cn = ComponentName(context, LumiNotificationService::class.java)
            return flat.contains(cn.flattenToString())
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        val title = extras.getString("android.title") ?: ""
        val text = (extras.getCharSequence("android.text") ?: "").toString()

        if (title.isBlank() && text.isBlank()) return

        val appName = packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(sbn.packageName, 0)
        ).toString()

        val captured = CapturedNotification(
            appName = appName,
            packageName = sbn.packageName,
            title = title,
            text = text,
            timestamp = sbn.postTime,
            key = sbn.key
        )

        synchronized(recentNotifications) {
            recentNotifications.removeIf { it.key == sbn.key }
            if (recentNotifications.size >= MAX_STORED) recentNotifications.removeFirst()
            recentNotifications.addLast(captured)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Keep in history even after dismissal
    }
}
