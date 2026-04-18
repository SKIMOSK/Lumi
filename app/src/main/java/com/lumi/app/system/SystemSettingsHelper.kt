package com.lumi.app.system

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.provider.Settings

class SystemSettingsHelper(private val context: Context) {

    fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    fun setBrightness(percent: Int): Result<Unit> {
        if (!canWriteSettings()) return Result.failure(
            SecurityException("Permission WRITE_SETTINGS not granted. Ask user to enable it in Settings > Apps > Special permissions.")
        )
        val raw = (percent.coerceIn(0, 100) * 255 / 100)
        Settings.System.putInt(context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
        return Result.success(Unit)
    }

    fun getBrightness(): Int {
        val raw = Settings.System.getInt(context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS, 128)
        return raw * 100 / 255
    }

    fun setVolume(stream: String, percent: Int) {
        val mgr = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamType = when (stream.lowercase()) {
            "media", "music", "muzica" -> AudioManager.STREAM_MUSIC
            "ring", "ringer", "sonerie" -> AudioManager.STREAM_RING
            "alarm", "alarma" -> AudioManager.STREAM_ALARM
            "notification", "notificari" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        val max = mgr.getStreamMaxVolume(streamType)
        mgr.setStreamVolume(streamType, (percent.coerceIn(0, 100) * max / 100), 0)
    }

    fun getVolume(stream: String): Int {
        val mgr = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamType = when (stream.lowercase()) {
            "media", "music", "muzica" -> AudioManager.STREAM_MUSIC
            "ring", "ringer", "sonerie" -> AudioManager.STREAM_RING
            else -> AudioManager.STREAM_MUSIC
        }
        val max = mgr.getStreamMaxVolume(streamType)
        return if (max == 0) 0 else mgr.getStreamVolume(streamType) * 100 / max
    }

    fun canAccessDND(): Boolean {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return mgr.isNotificationPolicyAccessGranted
    }

    fun setDND(enabled: Boolean): Result<Unit> {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!mgr.isNotificationPolicyAccessGranted) return Result.failure(
            SecurityException("DND permission not granted. Ask user to grant Do Not Disturb access in Settings.")
        )
        mgr.setInterruptionFilter(
            if (enabled) NotificationManager.INTERRUPTION_FILTER_NONE
            else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return Result.success(Unit)
    }

    fun isDNDEnabled(): Boolean {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return mgr.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
    }
}
