package com.lumi.app.timer

import java.util.UUID

enum class TimerType { TIMER, STOPWATCH, ALARM }
enum class TimerState { RUNNING, PAUSED, DONE }

data class LumiTimer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: TimerType,
    val durationMs: Long = 0L,
    val alarmTimeMs: Long = 0L,
    var state: TimerState = TimerState.RUNNING,
    var remainingMs: Long = 0L,
    val startedAtMs: Long = System.currentTimeMillis()
)
