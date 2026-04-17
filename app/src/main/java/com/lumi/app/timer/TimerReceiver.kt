package com.lumi.app.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TimerReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TIMER_DONE = "com.lumi.app.TIMER_DONE"
        const val ACTION_INTERNAL = "com.lumi.app.INTERNAL_TIMER_DONE"
        const val EXTRA_TIMER_ID = "timer_id"
        const val EXTRA_TIMER_NAME = "timer_name"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TIMER_DONE) return
        val name = intent.getStringExtra(EXTRA_TIMER_NAME) ?: "Timer"
        // Forward to app components listening for timer completions
        context.sendBroadcast(Intent(ACTION_INTERNAL).apply {
            putExtra(EXTRA_TIMER_NAME, name)
            setPackage(context.packageName)
        })
    }
}
