package com.lumi.app.notifications

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DirectReply(
    val replyPendingIntent: android.app.PendingIntent,
    val remoteInput: RemoteInput,
    val resultKey: String
)

data class CapturedNotification(
    val appName: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val key: String,
    val directReply: DirectReply? = null
) {
    fun formatted(): String {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        return "[$time] $appName — $title: $text"
    }
}

class LumiNotificationService : NotificationListenerService() {

    companion object {
        private val recent = ArrayDeque<CapturedNotification>(50)
        private const val MAX = 50

        fun getRecent(limit: Int = 20): List<CapturedNotification> = recent.takeLast(limit)
        fun getFromApp(pkg: String, limit: Int = 10) = recent.filter { it.packageName == pkg }.takeLast(limit)

        fun formatSummary(limit: Int = 15): String {
            val n = getRecent(limit)
            return if (n.isEmpty()) "" else n.joinToString("\n") { it.formatted() }
        }

        /** Find a notification with a direct-reply action (e.g. WhatsApp message). */
        fun findReplyable(packageName: String, contactHint: String?): CapturedNotification? =
            getFromApp(packageName).lastOrNull { n ->
                n.directReply != null &&
                (contactHint == null || n.title.contains(contactHint, ignoreCase = true))
            }

        fun isEnabled(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(ComponentName(context, LumiNotificationService::class.java).flattenToString())
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        val title = extras.getString("android.title") ?: ""
        val text  = (extras.getCharSequence("android.text") ?: "").toString()
        if (title.isBlank() && text.isBlank()) return

        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) { sbn.packageName }

        // Extract direct-reply action if present
        val reply = sbn.notification?.actions
            ?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
            ?.let { action ->
                val ri = action.remoteInputs?.firstOrNull() ?: return@let null
                DirectReply(action.actionIntent, ri, ri.resultKey)
            }

        val captured = CapturedNotification(appName, sbn.packageName, title, text, sbn.postTime, sbn.key, reply)
        synchronized(recent) {
            recent.removeIf { it.key == sbn.key }
            if (recent.size >= MAX) recent.removeFirst()
            recent.addLast(captured)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) { /* keep in history */ }
}
