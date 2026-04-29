package com.lumi.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class HealthConnectHelper(private val context: Context) {

    companion object {
        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        )
    }

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private fun client(): HealthConnectClient? =
        if (isAvailable()) HealthConnectClient.getOrCreate(context) else null

    suspend fun getTodaySummary(): String {
        val client = client() ?: return "Health Connect nu este disponibil pe acest dispozitiv."

        val granted = client.permissionController.getGrantedPermissions()
        if (granted.isEmpty()) return "Permisiunile Health Connect nu sunt acordate."

        val sb = StringBuilder()
        val today = LocalDate.now(ZoneId.systemDefault())
        val start = today.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val end = Instant.now()
        val range = TimeRangeFilter.between(start, end)

        // Steps
        if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
            try {
                val req = ReadRecordsRequest(StepsRecord::class, timeRangeFilter = range)
                val steps = client.readRecords(req).records.sumOf { it.count }
                sb.appendLine("Pași azi: $steps")
            } catch (_: Exception) {}
        }

        // Calories
        val calPerm = HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
        val totalCalPerm = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
        if (calPerm in granted) {
            try {
                val req = ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, timeRangeFilter = range)
                val kcal = client.readRecords(req).records.sumOf { it.energy.inKilocalories }
                sb.appendLine("Calorii active azi: ${kcal.roundToInt()} kcal")
            } catch (_: Exception) {}
        } else if (totalCalPerm in granted) {
            try {
                val req = ReadRecordsRequest(TotalCaloriesBurnedRecord::class, timeRangeFilter = range)
                val kcal = client.readRecords(req).records.sumOf { it.energy.inKilocalories }
                sb.appendLine("Calorii totale azi: ${kcal.roundToInt()} kcal")
            } catch (_: Exception) {}
        }

        // Heart rate (latest reading)
        if (HealthPermission.getReadPermission(HeartRateRecord::class) in granted) {
            try {
                val req = ReadRecordsRequest(HeartRateRecord::class, timeRangeFilter = range)
                val samples = client.readRecords(req).records.flatMap { it.samples }
                if (samples.isNotEmpty()) {
                    val avg = samples.map { it.beatsPerMinute }.average().roundToInt()
                    val max = samples.maxOf { it.beatsPerMinute }
                    sb.appendLine("Puls: medie $avg bpm, max $max bpm")
                }
            } catch (_: Exception) {}
        }

        // Sleep (last night = yesterday midnight to today noon)
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
            try {
                val yesterday = today.minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
                val noon = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
                val sleepRange = TimeRangeFilter.between(yesterday, noon)
                val req = ReadRecordsRequest(SleepSessionRecord::class, timeRangeFilter = sleepRange)
                val sessions = client.readRecords(req).records
                if (sessions.isNotEmpty()) {
                    val totalMins = sessions.sumOf {
                        (it.endTime.epochSecond - it.startTime.epochSecond) / 60L
                    }
                    val h = totalMins / 60
                    val m = totalMins % 60
                    val lastStart = sessions.last().startTime
                        .let { java.time.LocalDateTime.ofInstant(it, ZoneId.systemDefault()) }
                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                    val lastEnd = sessions.last().endTime
                        .let { java.time.LocalDateTime.ofInstant(it, ZoneId.systemDefault()) }
                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                    sb.appendLine("Somn noaptea trecuta: ${h}h ${m}min ($lastStart – $lastEnd)")
                }
            } catch (_: Exception) {}
        }

        return if (sb.isBlank()) "Nu am găsit date de sănătate pentru astăzi." else sb.toString().trimEnd()
    }

    suspend fun getWeeklySummary(): String {
        val client = client() ?: return "Health Connect nu este disponibil."
        val granted = client.permissionController.getGrantedPermissions()
        if (granted.isEmpty()) return "Permisiunile Health Connect nu sunt acordate."

        val today = LocalDate.now(ZoneId.systemDefault())
        val weekAgo = today.minusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val end = Instant.now()
        val range = TimeRangeFilter.between(weekAgo, end)
        val sb = StringBuilder()

        if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
            try {
                val req = ReadRecordsRequest(StepsRecord::class, timeRangeFilter = range)
                val steps = client.readRecords(req).records.sumOf { it.count }
                val avg = steps / 7
                sb.appendLine("Pași ultima săptămână: $steps total (~$avg/zi)")
            } catch (_: Exception) {}
        }

        return if (sb.isBlank()) "Nu am găsit date pentru ultima săptămână." else sb.toString().trimEnd()
    }
}
