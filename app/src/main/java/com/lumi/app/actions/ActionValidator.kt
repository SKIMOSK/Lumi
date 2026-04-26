package com.lumi.app.actions

/**
 * Pre-execution validator for [LumiAction]s emitted by the AI.
 *
 * The AI is told the JSON schema in the system prompt, but it can still slip:
 *   - misspell the action type
 *   - send "level":"abc" where an integer is required
 *   - omit a required parameter (e.g. SEND_WHATSAPP without "contact")
 *   - use an unrecognised value for an enum-style field (e.g. stream="speaker")
 *
 * Any of those would cause an ActionExecutor to either crash, no-op, or do
 * something unexpected. The validator runs first and:
 *   - drops actions that cannot possibly succeed (returns them as ERRORs)
 *   - flags suspicious values as WARNINGs but lets them through (executor has
 *     its own fallbacks for many of them)
 *
 * This is intentionally permissive: when in doubt we let the executor try,
 * because the executor already has graceful per-action error reporting.
 */
object ActionValidator {

    enum class Severity { ERROR, WARNING }

    data class Issue(val action: LumiAction, val reason: String, val severity: Severity)

    data class Report(
        val valid: List<LumiAction>,
        val issues: List<Issue>
    ) {
        val hasErrors get() = issues.any { it.severity == Severity.ERROR }
        fun summary(): String = issues.joinToString("\n") { i ->
            val tag = if (i.severity == Severity.ERROR) "Eroare validare" else "Avertisment"
            "$tag (${i.action.type}): ${i.reason}"
        }
    }

    fun validate(actions: List<LumiAction>): Report {
        val valid = mutableListOf<LumiAction>()
        val issues = mutableListOf<Issue>()
        for (a in actions) {
            val rule = RULES[a.type.uppercase()]
            if (rule == null) {
                issues += Issue(a, "tip de actiune necunoscut", Severity.ERROR)
                continue
            }
            val result = rule.validate(a)
            issues += result.issues
            if (result.fatal) continue
            valid += a
        }
        return Report(valid, issues)
    }

    // ─── Rule DSL ────────────────────────────────────────────────────────────

    private data class FieldCheck(
        val name: String,
        val required: Boolean = false,
        val type: ValueType = ValueType.STRING,
        val oneOf: Set<String>? = null,
        val range: IntRange? = null
    )

    private enum class ValueType { STRING, INT, LONG, BOOL, TIME_HHMM, DATETIME, FLOAT }

    private data class Rule(
        val type: String,
        val fields: List<FieldCheck>,
        /** At least one of these param groups must be present (each inner list = OR group of names). */
        val anyOf: List<List<String>> = emptyList(),
        /** Free-form extra check; returns null if OK or message if invalid. */
        val custom: ((LumiAction) -> String?)? = null
    ) {
        data class RuleResult(val issues: List<Issue>, val fatal: Boolean)

        fun validate(a: LumiAction): RuleResult {
            val issues = mutableListOf<Issue>()
            var fatal = false

            for (f in fields) {
                val v = a.params[f.name]
                if (v.isNullOrBlank()) {
                    if (f.required) {
                        issues += Issue(a, "lipseste parametrul obligatoriu '${f.name}'", Severity.ERROR)
                        fatal = true
                    }
                    continue
                }
                val typeIssue = checkType(v, f)
                if (typeIssue != null) {
                    issues += Issue(a, typeIssue, if (f.required) Severity.ERROR else Severity.WARNING)
                    if (f.required) fatal = true
                }
            }

            for (group in anyOf) {
                if (group.none { !a.params[it].isNullOrBlank() }) {
                    issues += Issue(a, "trebuie cel putin unul din: ${group.joinToString("/")}", Severity.ERROR)
                    fatal = true
                }
            }

            custom?.invoke(a)?.let {
                issues += Issue(a, it, Severity.ERROR)
                fatal = true
            }

            return RuleResult(issues, fatal)
        }

        private fun checkType(v: String, f: FieldCheck): String? {
            f.oneOf?.let { allowed ->
                if (v.lowercase() !in allowed.map { it.lowercase() })
                    return "'${f.name}=$v' nu e in lista permisa (${allowed.joinToString()})"
            }
            return when (f.type) {
                ValueType.STRING -> null
                ValueType.INT -> {
                    val i = v.toIntOrNull() ?: return "'${f.name}=$v' nu e numar intreg"
                    f.range?.let { if (i !in it) return "'${f.name}=$i' in afara intervalului ${it.first}-${it.last}" }
                    null
                }
                ValueType.LONG -> {
                    v.toLongOrNull() ?: return "'${f.name}=$v' nu e numar"
                    null
                }
                ValueType.BOOL -> {
                    if (v.lowercase() !in setOf("true", "false")) "'${f.name}=$v' trebuie 'true' sau 'false'" else null
                }
                ValueType.FLOAT -> {
                    v.toFloatOrNull() ?: return "'${f.name}=$v' nu e numar zecimal"
                    null
                }
                ValueType.TIME_HHMM -> {
                    val parts = v.split(":")
                    if (parts.size !in 2..3) return "'${f.name}=$v' nu e in format HH:mm"
                    val h = parts[0].toIntOrNull(); val m = parts[1].toIntOrNull()
                    if (h == null || m == null || h !in 0..23 || m !in 0..59)
                        return "'${f.name}=$v' nu e ora valida"
                    null
                }
                ValueType.DATETIME -> {
                    // accepted: yyyy-MM-dd'T'HH:mm  or  yyyy-MM-dd HH:mm
                    val ok = Regex("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2})?").matches(v)
                    if (!ok) "'${f.name}=$v' format invalid (asteptat yyyy-MM-ddTHH:mm)" else null
                }
            }
        }
    }

    // ─── Schema ──────────────────────────────────────────────────────────────

    private val STREAMS = setOf("media", "ring", "alarm", "notification")
    private val VOLUME_DIR = setOf(
        "up", "down", "mute", "max", "silent", "silentios", "tare", "incet",
        "creste", "scade", "increase", "decrease", "maximum"
    )
    private val MEDIA_CMDS = setOf(
        "play", "pause", "play_pause", "next", "previous", "stop", "open", "search"
    )
    private val MEDIA_APPS = setOf(
        "spotify", "youtube_music", "youtube", "netflix", "ytmusic", "apple_music"
    )
    private val MSG_APPS = setOf(
        "whatsapp", "telegram", "instagram", "snapchat", "facebook", "messenger",
        "discord", "slack", "signal", "sms", "messages"
    )
    private val FILE_APPS = MSG_APPS + setOf("email", "gmail", "outlook")

    private val RULES: Map<String, Rule> = listOf(
        // Timers / alarms
        Rule("SET_TIMER", listOf(
            FieldCheck("duration_seconds", required = true, type = ValueType.LONG),
            FieldCheck("name")
        )),
        Rule("SET_STOPWATCH", listOf(FieldCheck("name"))),
        Rule("SET_ALARM", listOf(
            FieldCheck("time_24h", required = true, type = ValueType.TIME_HHMM),
            FieldCheck("name")
        )),
        Rule("PAUSE_TIMER", listOf(FieldCheck("name"), FieldCheck("index", type = ValueType.INT))),
        Rule("PAUSE_STOPWATCH", listOf(FieldCheck("name"), FieldCheck("index", type = ValueType.INT))),
        Rule("RESUME_TIMER", listOf(FieldCheck("name"), FieldCheck("index", type = ValueType.INT))),
        Rule("RESUME_STOPWATCH", listOf(FieldCheck("name"), FieldCheck("index", type = ValueType.INT))),
        Rule("CANCEL_TIMER", listOf(FieldCheck("name"), FieldCheck("index", type = ValueType.INT))),
        Rule("CANCEL_STOPWATCH", listOf(FieldCheck("name"), FieldCheck("index", type = ValueType.INT))),
        Rule("CANCEL_ALARM", listOf(FieldCheck("name"), FieldCheck("index", type = ValueType.INT))),
        Rule("RESET_TIMER", listOf(FieldCheck("name"), FieldCheck("index", type = ValueType.INT))),
        Rule("RESET_STOPWATCH", listOf(FieldCheck("name"), FieldCheck("index", type = ValueType.INT))),

        // Messaging
        Rule("SEND_WHATSAPP", listOf(
            FieldCheck("contact", required = true),
            FieldCheck("message", required = true),
            FieldCheck("exact", type = ValueType.BOOL)
        )),
        Rule("SEND_SMS", listOf(
            FieldCheck("contact", required = true),
            FieldCheck("message", required = true)
        )),
        Rule("SEND_INSTAGRAM", listOf(
            FieldCheck("contact", required = true),
            FieldCheck("message", required = true),
            FieldCheck("username")
        )),
        Rule("SEND_SNAPCHAT", listOf(
            FieldCheck("contact", required = true),
            FieldCheck("message", required = true),
            FieldCheck("username")
        )),
        Rule("SEND_FACEBOOK", listOf(
            FieldCheck("contact", required = true),
            FieldCheck("message", required = true)
        )),
        Rule("SEND_DISCORD", listOf(
            FieldCheck("contact", required = true),
            FieldCheck("message", required = true),
            FieldCheck("username")
        )),
        Rule("SEND_TELEGRAM", listOf(
            FieldCheck("contact", required = true),
            FieldCheck("message", required = true)
        )),
        Rule("SEND_SLACK", listOf(
            FieldCheck("message", required = true),
            FieldCheck("channel"),
            FieldCheck("contact")
        ), anyOf = listOf(listOf("channel", "contact"))),
        Rule("SEND_EMAIL", listOf(
            FieldCheck("subject"),
            FieldCheck("body"),
            FieldCheck("message"),
            FieldCheck("to"),
            FieldCheck("contact"),
            FieldCheck("attachment_query"),
            FieldCheck("attach_pending", type = ValueType.BOOL)
        ), anyOf = listOf(listOf("to", "contact"), listOf("body", "message"))),
        Rule("READ_EMAIL", emptyList()),
        Rule("CALL", listOf(FieldCheck("contact", required = true))),

        // Notes
        Rule("WRITE_NOTE", listOf(
            FieldCheck("content", required = true),
            FieldCheck("title"),
            FieldCheck("id")
        )),
        Rule("WRITE_NOTE_APP", listOf(
            FieldCheck("app", required = true,
                oneOf = setOf("keep", "onenote", "obsidian", "samsung", "samsung_notes", "standardnotes", "notion")),
            FieldCheck("content", required = true),
            FieldCheck("title")
        )),
        Rule("DELETE_NOTE", listOf(
            FieldCheck("id"),
            FieldCheck("title")
        ), anyOf = listOf(listOf("id", "title"))),
        Rule("REMEMBER_FACT", listOf(FieldCheck("fact", required = true))),

        // Navigation
        Rule("NAVIGATE_MAPS", listOf(FieldCheck("destination", required = true))),
        Rule("NAVIGATE_WAZE", listOf(FieldCheck("destination", required = true))),

        // VPN
        Rule("CONNECT_VPN", listOf(
            FieldCheck("app", oneOf = setOf("surfshark", "nordvpn", "nord")),
            FieldCheck("country")
        )),
        Rule("DISCONNECT_VPN", listOf(
            FieldCheck("app", oneOf = setOf("surfshark", "nordvpn", "nord")),
            FieldCheck("country")
        )),

        // Calendar
        Rule("CREATE_EVENT", listOf(
            FieldCheck("title", required = true),
            FieldCheck("start_datetime", required = true, type = ValueType.DATETIME),
            FieldCheck("end_datetime", type = ValueType.DATETIME),
            FieldCheck("description"),
            FieldCheck("location")
        )),
        Rule("READ_CALENDAR", emptyList()),
        Rule("DELETE_EVENT", listOf(
            FieldCheck("id"),
            FieldCheck("title")
        ), anyOf = listOf(listOf("id", "title"))),
        Rule("UPDATE_EVENT", listOf(
            FieldCheck("id"),
            FieldCheck("lookup_title"),
            FieldCheck("title"),
            FieldCheck("new_title"),
            FieldCheck("description"),
            FieldCheck("location"),
            FieldCheck("start_datetime", type = ValueType.DATETIME),
            FieldCheck("end_datetime", type = ValueType.DATETIME)
        ), anyOf = listOf(listOf("id", "lookup_title"))),

        // System
        Rule("SET_BRIGHTNESS", listOf(
            FieldCheck("level", required = true, type = ValueType.INT, range = 0..100)
        )),
        Rule("SET_VOLUME", listOf(
            FieldCheck("stream", oneOf = STREAMS),
            FieldCheck("level", type = ValueType.INT, range = 0..100),
            FieldCheck("step", type = ValueType.INT, range = 1..100),
            FieldCheck("direction", oneOf = VOLUME_DIR)
        ), anyOf = listOf(listOf("level", "direction"))),
        Rule("SET_DND", listOf(FieldCheck("enabled", required = true, type = ValueType.BOOL))),
        Rule("SET_BATTERY_SAVER", listOf(FieldCheck("enabled", required = true, type = ValueType.BOOL))),
        Rule("SET_TTS_SPEED", listOf(
            FieldCheck("speed", type = ValueType.FLOAT),
            FieldCheck("direction", oneOf = setOf("faster", "slower"))
        ), anyOf = listOf(listOf("speed", "direction"))),

        // Media
        Rule("MEDIA_CONTROL", listOf(
            FieldCheck("command", required = true, oneOf = MEDIA_CMDS),
            FieldCheck("app", oneOf = MEDIA_APPS),
            FieldCheck("query")
        )),
        Rule("YOUTUBE_SEARCH", listOf(FieldCheck("query", required = true))),
        Rule("YOUTUBE_WATCH_LATER", listOf(FieldCheck("query"))),
        Rule("YOUTUBE_LIBRARY", emptyList()),
        Rule("HOME_CONTROL", listOf(
            FieldCheck("app"),
            FieldCheck("device"),
            FieldCheck("action")
        )),

        // Health / finance / shopping
        Rule("READ_HEALTH", listOf(FieldCheck("app",
            oneOf = setOf("google_fit", "fit", "strava", "myfitnesspal", "health_connect", "samsung_health")))),
        Rule("READ_BALANCE", listOf(FieldCheck("app",
            oneOf = setOf("revolut", "wise", "paypal", "bt", "btpay", "banca", "transilvania")))),
        Rule("SHOP_SEARCH", listOf(
            FieldCheck("query", required = true),
            FieldCheck("app", oneOf = setOf("amazon", "ebay", "aliexpress"))
        )),
        Rule("SHOP_TRACK", listOf(FieldCheck("app", oneOf = setOf("amazon", "ebay", "aliexpress")))),
        Rule("READ_CRYPTO", listOf(FieldCheck("app",
            oneOf = setOf("binance", "coinbase", "metamask", "trustwallet")))),
        Rule("FETCH_NEWS", listOf(FieldCheck("topic"))),

        // Delivery / rideshare
        Rule("FOOD_DELIVERY", listOf(
            FieldCheck("food", required = true),
            FieldCheck("app", oneOf = setOf("ubereats", "doordash", "deliveroo", "uber_eats")),
            FieldCheck("restaurant"),
            FieldCheck("customization")
        )),
        Rule("RIDESHARE", listOf(
            FieldCheck("destination", required = true),
            FieldCheck("app", oneOf = setOf("uber", "lyft")),
            FieldCheck("pickup"),
            FieldCheck("ride_type")
        )),

        // Bluetooth
        Rule("BT_LIST_DEVICES", emptyList()),
        Rule("BT_CONNECT", listOf(FieldCheck("device", required = true))),
        Rule("BT_DISCONNECT", listOf(FieldCheck("device", required = true))),
        Rule("BT_PAIR", listOf(FieldCheck("device", required = true))),

        // Gallery / files
        Rule("GALLERY_SEARCH", listOf(
            FieldCheck("query"),
            FieldCheck("from_date"),
            FieldCheck("to_date"),
            FieldCheck("limit", type = ValueType.INT, range = 1..200),
            FieldCheck("offset", type = ValueType.INT, range = 0..10000)
        )),
        Rule("SEND_IMAGE", listOf(
            FieldCheck("app", oneOf = MSG_APPS),
            FieldCheck("contact"),
            FieldCheck("image_id", type = ValueType.LONG),
            FieldCheck("use_pending", type = ValueType.BOOL),
            FieldCheck("use_gallery_result", type = ValueType.INT, range = 0..199)
        ), anyOf = listOf(listOf("image_id", "use_pending", "use_gallery_result"))),
        Rule("FORWARD_FILE", listOf(
            FieldCheck("app", required = true, oneOf = FILE_APPS),
            FieldCheck("contact", required = true),
            FieldCheck("query", required = true),
            FieldCheck("subject"),
            FieldCheck("body")
        )),
        Rule("CREATE_FORWARD_FILE", listOf(
            FieldCheck("app", required = true, oneOf = FILE_APPS),
            FieldCheck("contact", required = true),
            FieldCheck("filename", required = true),
            FieldCheck("new_content", required = true),
            FieldCheck("subject"),
            FieldCheck("body")
        )),
        Rule("SEND_FILE", listOf(
            FieldCheck("app", required = true, oneOf = FILE_APPS),
            FieldCheck("contact"),
            FieldCheck("subject"),
            FieldCheck("body")
        )),
        Rule("EDIT_FILE", listOf(FieldCheck("new_content", required = true))),

        // Notifications
        Rule("REPLY_NOTIFICATION", listOf(
            FieldCheck("message", required = true),
            FieldCheck("notification_key"),
            FieldCheck("contact"),
            FieldCheck("app")
        ))
    ).associateBy { it.type }
}
