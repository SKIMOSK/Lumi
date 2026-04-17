package com.lumi.app.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.CountDownTimer
import java.util.Calendar

class TimerManager(private val context: Context) {

    private val timers = mutableMapOf<String, LumiTimer>()
    private val countdowns = mutableMapOf<String, CountDownTimer>()
    private val stopwatchStarts = mutableMapOf<String, Long>()      // timerId → epochMs start
    private val stopwatchPaused = mutableMapOf<String, Long>()       // timerId → total paused ms

    var onTimerDone: ((LumiTimer) -> Unit)? = null

    // ─── Public API ──────────────────────────────────────────────────────────

    fun setTimer(name: String, durationSeconds: Long): LumiTimer {
        getByName(name)?.let { cancel(it.id) }
        val timer = LumiTimer(name = name, type = TimerType.TIMER,
            durationMs = durationSeconds * 1000, remainingMs = durationSeconds * 1000)
        timers[timer.id] = timer
        startCountdown(timer)
        return timer
    }

    fun startStopwatch(name: String): LumiTimer {
        getByName(name)?.let { cancel(it.id) }
        val timer = LumiTimer(name = name, type = TimerType.STOPWATCH)
        timers[timer.id] = timer
        stopwatchStarts[timer.id] = System.currentTimeMillis()
        stopwatchPaused[timer.id] = 0L
        return timer
    }

    fun setAlarm(name: String, timeMs: Long): LumiTimer {
        getByName(name)?.let { cancel(it.id) }
        val timer = LumiTimer(name = name, type = TimerType.ALARM, alarmTimeMs = timeMs)
        timers[timer.id] = timer
        scheduleAlarm(timer)
        return timer
    }

    fun pause(id: String) {
        val t = timers[id]?.takeIf { it.state == TimerState.RUNNING } ?: return
        t.state = TimerState.PAUSED
        when (t.type) {
            TimerType.TIMER -> { countdowns[id]?.cancel(); countdowns.remove(id) }
            TimerType.STOPWATCH -> {
                val elapsed = stopwatchPaused[id] ?: 0L
                stopwatchPaused[id] = elapsed + (System.currentTimeMillis() - (stopwatchStarts[id] ?: 0L))
                stopwatchStarts.remove(id)
            }
            TimerType.ALARM -> {}
        }
    }

    fun resume(id: String) {
        val t = timers[id]?.takeIf { it.state == TimerState.PAUSED } ?: return
        t.state = TimerState.RUNNING
        when (t.type) {
            TimerType.TIMER -> startCountdown(t)
            TimerType.STOPWATCH -> stopwatchStarts[id] = System.currentTimeMillis()
            TimerType.ALARM -> {}
        }
    }

    fun reset(id: String) {
        val t = timers[id] ?: return
        when (t.type) {
            TimerType.TIMER -> {
                countdowns[id]?.cancel()
                t.remainingMs = t.durationMs
                t.state = TimerState.RUNNING
                startCountdown(t)
            }
            TimerType.STOPWATCH -> {
                stopwatchPaused[id] = 0L
                if (t.state == TimerState.RUNNING) stopwatchStarts[id] = System.currentTimeMillis()
            }
            TimerType.ALARM -> {}
        }
    }

    fun cancel(id: String) {
        countdowns[id]?.cancel()
        countdowns.remove(id)
        stopwatchStarts.remove(id)
        stopwatchPaused.remove(id)
        cancelAlarm(id)
        timers.remove(id)
    }

    fun getByName(name: String) = timers.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
    fun getAll(): List<LumiTimer> = timers.values.toList()

    fun getElapsedMs(id: String): Long {
        val t = timers[id] ?: return 0L
        if (t.type != TimerType.STOPWATCH) return t.durationMs - t.remainingMs
        val base = stopwatchPaused[id] ?: 0L
        val start = stopwatchStarts[id] ?: return base
        return base + (System.currentTimeMillis() - start)
    }

    fun formatStatus(): String {
        val all = getAll()
        if (all.isEmpty()) return "Nu există timere active."
        return all.joinToString("\n") { t ->
            when (t.type) {
                TimerType.TIMER -> "${t.name}: ${fmtSecs(t.remainingMs / 1000)} rămase [${t.state}]"
                TimerType.STOPWATCH -> "${t.name}: ${fmtMs(getElapsedMs(t.id))} [${t.state}]"
                TimerType.ALARM -> "${t.name}: ${fmtAlarm(t.alarmTimeMs)} [${t.state}]"
            }
        }
    }

    // ─── Private ─────────────────────────────────────────────────────────────

    private fun startCountdown(t: LumiTimer) {
        val cd = object : CountDownTimer(t.remainingMs, 1000) {
            override fun onTick(rem: Long) { t.remainingMs = rem }
            override fun onFinish() {
                t.state = TimerState.DONE
                t.remainingMs = 0
                countdowns.remove(t.id)
                onTimerDone?.invoke(t)
            }
        }.start()
        countdowns[t.id] = cd
    }

    private fun scheduleAlarm(t: LumiTimer) {
        val mgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(t.id, t.name)
        mgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.alarmTimeMs, pi)
    }

    private fun cancelAlarm(id: String) {
        val mgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(context, id.hashCode(),
            Intent(context, TimerReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) ?: return
        mgr.cancel(pi)
    }

    private fun alarmIntent(id: String, name: String): PendingIntent {
        val intent = Intent(context, TimerReceiver::class.java).apply {
            action = TimerReceiver.ACTION_TIMER_DONE
            putExtra(TimerReceiver.EXTRA_TIMER_ID, id)
            putExtra(TimerReceiver.EXTRA_TIMER_NAME, name)
        }
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
    }

    fun fmtSecs(s: Long): String {
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "${h}h ${m}m ${sec}s" else if (m > 0) "${m}m ${sec}s" else "${sec}s"
    }
    fun fmtMs(ms: Long) = fmtSecs(ms / 1000)
    private fun fmtAlarm(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }
}
