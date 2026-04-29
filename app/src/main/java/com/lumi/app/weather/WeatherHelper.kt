package com.lumi.app.weather

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class WeatherHelper {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Fetch current conditions + 3-day forecast. Returns a human-readable string. */
    fun getWeather(lat: Double, lon: Double, lang: String = "ro"): String {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum" +
            "&wind_speed_unit=kmh&forecast_days=3&timezone=auto"

        val json = try {
            val resp = http.newCall(Request.Builder().url(url).build()).execute()
            if (!resp.isSuccessful) return "Nu am putut obține datele meteo (${resp.code})."
            resp.body?.string() ?: return "Răspuns gol de la serverul meteo."
        } catch (e: Exception) {
            return "Eroare conexiune meteo: ${e.message}"
        }

        return parseWeather(json, lang)
    }

    private fun parseWeather(json: String, lang: String): String {
        return try {
            val root = JSONObject(json)
            val cur = root.getJSONObject("current")
            val daily = root.getJSONObject("daily")

            val tempNow = cur.getDouble("temperature_2m").roundToInt()
            val feelsLike = cur.getDouble("apparent_temperature").roundToInt()
            val humidity = cur.getInt("relative_humidity_2m")
            val wind = cur.getDouble("wind_speed_10m").roundToInt()
            val codeNow = cur.getInt("weather_code")
            val descNow = wmoDescription(codeNow, lang)

            val dates = daily.getJSONArray("time")
            val maxTemps = daily.getJSONArray("temperature_2m_max")
            val minTemps = daily.getJSONArray("temperature_2m_min")
            val codes = daily.getJSONArray("weather_code")
            val precip = daily.getJSONArray("precipitation_sum")

            val sb = StringBuilder()
            sb.appendLine("Acum: $tempNow°C (se simte ca $feelsLike°C), $descNow")
            sb.appendLine("Umiditate: $humidity% | Vânt: $wind km/h")
            sb.appendLine()
            sb.appendLine("Prognoză 3 zile:")
            for (i in 0 until minOf(3, dates.length())) {
                val date = dates.getString(i)
                val maxT = maxTemps.getDouble(i).roundToInt()
                val minT = minTemps.getDouble(i).roundToInt()
                val desc = wmoDescription(codes.getInt(i), lang)
                val rain = precip.getDouble(i)
                val rainStr = if (rain > 0.1) ", precipitații ${rain}mm" else ""
                sb.appendLine("  ${formatDate(date, i)}: $minT–$maxT°C, $desc$rainStr")
            }

            sb.toString().trimEnd()
        } catch (e: Exception) {
            "Nu am putut interpreta datele meteo: ${e.message}"
        }
    }

    private fun formatDate(isoDate: String, offset: Int): String = when (offset) {
        0 -> "Azi"
        1 -> "Mâine"
        else -> {
            // Parse yyyy-MM-dd and get day name
            val parts = isoDate.split("-")
            if (parts.size == 3) {
                val cal = java.util.Calendar.getInstance()
                cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                val days = arrayOf("Dum", "Lun", "Mar", "Mie", "Joi", "Vin", "Sâm")
                "${days[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]} ${parts[2]}.${parts[1]}"
            } else isoDate
        }
    }

    private fun wmoDescription(code: Int, lang: String): String {
        val ro = when (code) {
            0 -> "cer senin"
            1 -> "în mare parte senin"
            2 -> "parțial noros"
            3 -> "noros"
            45, 48 -> "ceață"
            51 -> "burniță ușoară"
            53 -> "burniță moderată"
            55 -> "burniță densă"
            61 -> "ploaie ușoară"
            63 -> "ploaie moderată"
            65 -> "ploaie puternică"
            71 -> "ninsoare ușoară"
            73 -> "ninsoare moderată"
            75 -> "ninsoare puternică"
            77 -> "granule de zăpadă"
            80 -> "averse ușoare"
            81 -> "averse moderate"
            82 -> "averse puternice"
            85, 86 -> "averse de zăpadă"
            95 -> "furtună cu tunete"
            96, 99 -> "furtună cu grindină"
            else -> "condiții variabile"
        }
        return if (lang.startsWith("ro")) ro else wmoDescriptionEn(code)
    }

    private fun wmoDescriptionEn(code: Int): String = when (code) {
        0 -> "clear sky"
        1 -> "mainly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51 -> "light drizzle"
        53 -> "moderate drizzle"
        55 -> "dense drizzle"
        61 -> "light rain"
        63 -> "moderate rain"
        65 -> "heavy rain"
        71 -> "light snow"
        73 -> "moderate snow"
        75 -> "heavy snow"
        80 -> "light showers"
        81 -> "moderate showers"
        82 -> "heavy showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "variable conditions"
    }
}
