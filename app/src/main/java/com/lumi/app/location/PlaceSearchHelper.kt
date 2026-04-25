package com.lumi.app.location

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class PlaceSearchHelper {

    data class Place(val name: String, val address: String, val lat: Double, val lon: Double)

    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    suspend fun search(query: String, lat: Double, lon: Double, limit: Int = 3): List<Place> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search" +
                "?q=$q&lat=$lat&lon=$lon" +
                "&format=json&limit=$limit&addressdetails=1"
            fetch(url) { parseSearch(it) } ?: emptyList()
        }

    suspend fun reverseGeocode(lat: Double, lon: Double): String? =
        withContext(Dispatchers.IO) {
            val url = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json"
            fetch(url) { parseCity(it) }
        }

    private fun <T> fetch(url: String, parse: (String) -> T): T? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Lumi Android AI/1.0")
                .header("Accept-Language", "ro,en;q=0.9")
                .build()
            val body = client.newCall(req).execute().use { it.body?.string() } ?: return null
            parse(body)
        } catch (_: Exception) { null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSearch(json: String): List<Place> {
        val arr = gson.fromJson(json, List::class.java) as? List<*> ?: return emptyList()
        return arr.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val display = m["display_name"] as? String ?: return@mapNotNull null
            val lat = (m["lat"] as? String)?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = (m["lon"] as? String)?.toDoubleOrNull() ?: return@mapNotNull null
            val short = display.split(",").take(3).joinToString(",").trim()
            Place(short, display, lat, lon)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseCity(json: String): String? {
        val m = gson.fromJson(json, Map::class.java) ?: return null
        val addr = m["address"] as? Map<*, *> ?: return null
        return listOf("city", "town", "municipality", "village", "county")
            .firstNotNullOfOrNull { addr[it] as? String }
    }
}
