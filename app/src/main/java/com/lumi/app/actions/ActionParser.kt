package com.lumi.app.actions

import com.google.gson.Gson
import com.google.gson.JsonParser

data class LumiAction(
    val type: String,
    val params: Map<String, String> = emptyMap()
)

data class DataRequest(
    val notifications: Boolean = false,
    val contacts: List<String> = emptyList(),
    val whatsapp: WhatsAppRequest? = null,
    val timers: Boolean = false,
    val notes: Boolean = false,
    val notes_query: String? = null
)

data class WhatsAppRequest(val contact: String, val limit: Int = 25)

data class ParsedAIResponse(
    val displayText: String,
    val actions: List<LumiAction> = emptyList(),
    val dataRequest: DataRequest? = null
)

object ActionParser {
    const val ACTIONS_MARKER = "___LUMI_ACTIONS___"
    const val REQUEST_MARKER = "___LUMI_REQUEST___"
    private val gson = Gson()

    fun parse(raw: String): ParsedAIResponse {
        var text = raw
        var actions = listOf<LumiAction>()
        var dataRequest: DataRequest? = null

        if (text.contains(REQUEST_MARKER)) {
            val parts = text.split(REQUEST_MARKER, limit = 2)
            text = parts[0].trim()
            dataRequest = tryParse<DataRequest>(parts.getOrNull(1)?.trim())
        }

        if (text.contains(ACTIONS_MARKER)) {
            val parts = text.split(ACTIONS_MARKER, limit = 2)
            text = parts[0].trim()
            actions = parseActions(parts.getOrNull(1)?.trim())
        }

        return ParsedAIResponse(text, actions, dataRequest)
    }

    private fun parseActions(json: String?): List<LumiAction> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JsonParser.parseString(json).asJsonObject["actions"]?.asJsonArray ?: return emptyList()
            arr.mapNotNull { el ->
                val o = el.asJsonObject
                val type = o["type"]?.asString ?: return@mapNotNull null
                val params = o.entrySet()
                    .filter { it.key != "type" }
                    .associate { (k, v) ->
                        k to when {
                            v.isJsonPrimitive && v.asJsonPrimitive.isNumber ->
                                v.asLong.toString()
                            else -> v.asString
                        }
                    }
                LumiAction(type, params)
            }
        } catch (e: Exception) { emptyList() }
    }

    private inline fun <reified T> tryParse(json: String?): T? {
        if (json.isNullOrBlank()) return null
        return try { gson.fromJson(json, T::class.java) } catch (e: Exception) { null }
    }
}
