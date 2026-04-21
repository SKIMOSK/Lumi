package com.lumi.app.calendar

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.*

class CalendarHelper(private val context: Context) {

    data class Event(
        val id: Long,
        val title: String,
        val description: String?,
        val location: String?,
        val startMs: Long,
        val endMs: Long,
        val allDay: Boolean
    ) {
        fun formatted(): String {
            val sdf = if (allDay) SimpleDateFormat("EEE d MMM", Locale.getDefault())
                      else SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
            val start = sdf.format(Date(startMs))
            return "$title @ $start${if (!location.isNullOrBlank()) " [$location]" else ""}"
        }
    }

    fun getUpcoming(limit: Int = 10): List<Event> {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY
        )
        val events = mutableListOf<Event>()
        return try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DELETED} = 0",
                arrayOf(System.currentTimeMillis().toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { c ->
                while (c.moveToNext() && events.size < limit) {
                    events.add(Event(
                        c.getLong(0), c.getString(1) ?: "Fara titlu",
                        c.getString(2), c.getString(3),
                        c.getLong(4), c.getLong(5), c.getInt(6) == 1
                    ))
                }
            }
            events
        } catch (e: Exception) { emptyList() }
    }

    fun create(title: String, description: String?, location: String?, startMs: Long, endMs: Long): Boolean {
        val calId = getPrimaryCalendarId()
        if (calId != null) {
            try {
                val values = ContentValues().apply {
                    put(CalendarContract.Events.TITLE, title)
                    description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                    location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                    put(CalendarContract.Events.DTSTART, startMs)
                    put(CalendarContract.Events.DTEND, endMs)
                    put(CalendarContract.Events.CALENDAR_ID, calId)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                }
                val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                if (uri != null) return true
            } catch (e: Exception) { /* fall through to UI */ }
        }
        // Fallback: open calendar app UI pre-filled
        context.startActivity(Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        return true
    }

    fun formatSummary(limit: Int = 10): String {
        val events = getUpcoming(limit)
        return if (events.isEmpty()) "Niciun eveniment viitor in calendar."
               else events.joinToString("\n") { it.formatted() }
    }

    private fun getPrimaryCalendarId(): Long? {
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.VISIBLE} = 1",
                null, "${CalendarContract.Calendars._ID} ASC"
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        } catch (e: Exception) { null }
    }
}
