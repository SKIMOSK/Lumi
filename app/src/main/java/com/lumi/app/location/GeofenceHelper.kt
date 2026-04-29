package com.lumi.app.location

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

data class LocationReminder(
    val id: String,
    val locationName: String,
    val reminderText: String,
    val triggerOnEnter: Boolean = true,
    val triggerOnExit: Boolean = false
)

class GeofenceHelper(private val context: Context) {

    companion object {
        const val ACTION_GEOFENCE = "com.lumi.app.ACTION_GEOFENCE_TRANSITION"
        const val EXTRA_REMINDER_TEXT = "reminder_text"
        const val EXTRA_LOCATION_NAME = "location_name"
        const val EXTRA_TRIGGER = "trigger_type"
    }

    private val client: GeofencingClient = LocationServices.getGeofencingClient(context)

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun canAddGeofences(): Boolean = hasFineLocation() && hasBackgroundLocation()

    fun addReminder(
        userLocation: UserLocation,
        reminder: LocationReminder,
        onResult: (Boolean, String) -> Unit
    ) {
        if (!canAddGeofences()) {
            onResult(false, "Permisiunea pentru locație în fundal lipsește.")
            return
        }

        val transitionTypes = buildList {
            if (reminder.triggerOnEnter) add(Geofence.GEOFENCE_TRANSITION_ENTER)
            if (reminder.triggerOnExit) add(Geofence.GEOFENCE_TRANSITION_EXIT)
        }.fold(0) { acc, t -> acc or t }

        val geofence = Geofence.Builder()
            .setRequestId(reminder.id)
            .setCircularRegion(userLocation.lat, userLocation.lon, userLocation.radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(transitionTypes)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        val pendingIntent = buildPendingIntent(reminder)

        try {
            client.addGeofences(request, pendingIntent)
                .addOnSuccessListener { onResult(true, "Reminder setat: ${reminder.reminderText} (la ${userLocation.name})") }
                .addOnFailureListener { e -> onResult(false, "Nu s-a putut seta geofence: ${e.message}") }
        } catch (e: SecurityException) {
            onResult(false, "Permisiune locație lipsă: ${e.message}")
        }
    }

    fun removeGeofence(id: String) {
        client.removeGeofences(listOf(id))
    }

    private fun buildPendingIntent(reminder: LocationReminder): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = ACTION_GEOFENCE
            putExtra(EXTRA_REMINDER_TEXT, reminder.reminderText)
            putExtra(EXTRA_LOCATION_NAME, reminder.locationName)
            putExtra(EXTRA_TRIGGER, if (reminder.triggerOnEnter) "enter" else "exit")
        }
        return PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
}
