package com.lumi.app.location

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class UserLocation(
    val name: String,          // "acasa", "birou", "sala"
    val address: String,
    val lat: Double,
    val lon: Double,
    val radiusMeters: Float = 150f
)

class UserLocations(context: Context) {

    private val file = File(context.filesDir, "user_locations.json")
    private val gson = Gson()
    private val locations = mutableListOf<UserLocation>()

    init { load() }

    private fun load() {
        try {
            if (!file.exists()) return
            val type = object : TypeToken<List<UserLocation>>() {}.type
            val loaded: List<UserLocation>? = gson.fromJson(file.readText(), type)
            loaded?.let { locations.addAll(it) }
        } catch (_: Exception) {}
    }

    private fun save() {
        try { file.writeText(gson.toJson(locations)) } catch (_: Exception) {}
    }

    fun getAll(): List<UserLocation> = locations.toList()

    fun findByName(name: String): UserLocation? {
        val n = name.trim().lowercase()
        return locations.firstOrNull { it.name.lowercase() == n }
            ?: locations.firstOrNull { it.name.lowercase().contains(n) }
    }

    fun add(location: UserLocation) {
        locations.removeAll { it.name.lowercase() == location.name.lowercase() }
        locations.add(location)
        save()
    }

    fun remove(name: String): Boolean {
        val removed = locations.removeAll { it.name.lowercase() == name.trim().lowercase() }
        if (removed) save()
        return removed
    }

    fun formatAll(): String {
        if (locations.isEmpty()) return "Nicio locație salvată."
        return locations.joinToString("\n") { l ->
            "${l.name}: ${l.address} (${l.lat}, ${l.lon})"
        }
    }
}
