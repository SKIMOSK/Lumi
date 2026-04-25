package com.lumi.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

class LocationHelper(private val context: Context) {

    data class GpsLocation(val lat: Double, val lon: Double, val accuracy: Float = 0f)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun getLastKnown(): GpsLocation? {
        if (!hasPermission()) return null
        val mgr = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var best: Location? = null
        for (provider in providers) {
            try {
                val loc = mgr.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.accuracy < best.accuracy) best = loc
            } catch (_: Exception) {}
        }
        return best?.let { GpsLocation(it.latitude, it.longitude, it.accuracy) }
    }

    fun format(loc: GpsLocation): String = "%.5f,%.5f".format(loc.lat, loc.lon)
}
