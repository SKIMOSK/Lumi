package com.lumi.app.packages

import android.content.Context
import android.provider.Telephony

data class TrackedPackage(
    val trackingNumber: String,
    val carrier: String,
    val trackingUrl: String,
    val smsSender: String,
    val smsDate: String,
    val smsSnippet: String
)

class PackageTrackingHelper(private val context: Context) {

    companion object {
        // Carrier-specific tracking number patterns
        private val PATTERNS = listOf(
            // UPS: 1Z + 16 alphanumeric
            Pair("UPS", Regex("""1Z[0-9A-Z]{16}""", RegexOption.IGNORE_CASE)),
            // FedEx: 12, 15, or 20 digits
            Pair("FedEx", Regex("""\b(\d{12}|\d{15}|\d{20})\b""")),
            // DHL Express: JD + 18 digits or 10+ digits starting with 0
            Pair("DHL", Regex("""JD\d{18}|0{1}\d{9,}\b""", RegexOption.IGNORE_CASE)),
            // USPS: 20-22 digit or 9400/9205/9261 prefix
            Pair("USPS", Regex("""\b(9[2-5]\d{18,20}|[A-Z]{2}\d{9}US)\b""", RegexOption.IGNORE_CASE)),
            // GLS: 11 digit starting with 9
            Pair("GLS", Regex("""\b9\d{10}\b""")),
            // DPD: 14 digit
            Pair("DPD", Regex("""\b\d{14}\b""")),
            // Romanian Post / Fan Courier: RO + alphanumeric
            Pair("Posta Romana", Regex("""RO\w{9,}\b""", RegexOption.IGNORE_CASE)),
            // Amazon: TBA + 12 digits or 17-char alphanumeric with letters
            Pair("Amazon", Regex("""TBA\d{12}|(?:TBA|[A-Z]{2}\d{7})[A-Z0-9]{5,}""", RegexOption.IGNORE_CASE)),
        )

        private val COURIER_KEYWORDS = setOf(
            "tracking", "colet", "expediat", "livrare", "awb", "comandă", "comanda",
            "package", "shipment", "delivery", "order", "shipped", "dispatch",
            "curier", "dpd", "gls", "fan courier", "sameday", "urgent cargus"
        )

        private val TRACKING_URL_TEMPLATES = mapOf(
            "UPS"           to "https://www.ups.com/track?tracknum=%s",
            "FedEx"         to "https://www.fedex.com/fedextrack/?trknbr=%s",
            "DHL"           to "https://www.dhl.com/en/express/tracking.html?AWB=%s",
            "USPS"          to "https://tools.usps.com/go/TrackConfirmAction?tLabels=%s",
            "GLS"           to "https://gls-group.eu/track/%s",
            "DPD"           to "https://tracking.dpd.de/parcelstatus?query=%s",
            "Posta Romana"  to "https://www.posta-romana.ro/tracking/%s",
            "Amazon"        to "https://track.amazon.com/tracking/%s",
        )
    }

    fun findTrackingNumbers(limitMessages: Int = 200): List<TrackedPackage> {
        val results = mutableListOf<TrackedPackage>()
        val seenNumbers = mutableSetOf<String>()

        try {
            val uri = Telephony.Sms.Inbox.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            )
            context.contentResolver.query(
                uri, projection, null, null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                val addrIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
                var count = 0
                while (cursor.moveToNext() && count < limitMessages) {
                    count++
                    val sender = cursor.getString(addrIdx) ?: continue
                    val body = cursor.getString(bodyIdx) ?: continue
                    val dateMs = cursor.getLong(dateIdx)

                    if (!looksLikeCourierSms(body)) continue

                    for ((carrier, pattern) in PATTERNS) {
                        pattern.find(body)?.let { match ->
                            val num = match.value.uppercase()
                            if (num !in seenNumbers) {
                                seenNumbers.add(num)
                                val url = TRACKING_URL_TEMPLATES[carrier]?.format(num)
                                    ?: "https://www.google.com/search?q=$num+tracking"
                                val smsDate = android.text.format.DateFormat
                                    .format("dd.MM.yyyy HH:mm", dateMs).toString()
                                results.add(
                                    TrackedPackage(
                                        trackingNumber = num,
                                        carrier = carrier,
                                        trackingUrl = url,
                                        smsSender = sender,
                                        smsDate = smsDate,
                                        smsSnippet = body.take(120)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: SecurityException) {
            // READ_SMS permission not granted
        }

        return results
    }

    fun formatForDisplay(packages: List<TrackedPackage>): String {
        if (packages.isEmpty()) return "Nu am găsit numere de urmărire în SMS-urile recente."
        val sb = StringBuilder("Colete găsite în SMS-uri:\n")
        packages.forEachIndexed { i, p ->
            sb.appendLine("[${i + 1}] ${p.carrier}: ${p.trackingNumber}")
            sb.appendLine("    Data: ${p.smsDate} | De la: ${p.smsSender}")
            sb.appendLine("    Urmărire: ${p.trackingUrl}")
        }
        return sb.toString().trimEnd()
    }

    private fun looksLikeCourierSms(body: String): Boolean {
        val lower = body.lowercase()
        return COURIER_KEYWORDS.any { lower.contains(it) }
    }
}
