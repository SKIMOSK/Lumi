package com.lumi.app.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Interaction(
    val userText: String,
    val assistantText: String,
    val imageBase64: String? = null,
    val usedProModel: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun formattedTime(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}

class ConversationMemory(private var maxSize: Int = 5) {

    private val history = ArrayDeque<Interaction>()

    fun updateMaxSize(newSize: Int) {
        maxSize = newSize.coerceAtLeast(0)
        while (history.size > maxSize) history.removeFirst()
    }

    fun add(interaction: Interaction) {
        if (maxSize == 0) return  // 0 = remember nothing beyond current query
        if (history.size >= maxSize) history.removeFirst()
        history.addLast(interaction)
    }

    fun getAll(): List<Interaction> = history.toList()
    fun clear() = history.clear()

    /** Formats history as Gemini/OpenAI "contents" entries for the API. */
    fun toGeminiContents(): List<Map<String, Any>> {
        val contents = mutableListOf<Map<String, Any>>()
        for (i in history) {
            val userParts = mutableListOf<Map<String, Any>>()
            userParts.add(mapOf("text" to i.userText))
            i.imageBase64?.let { img ->
                userParts.add(mapOf("inlineData" to mapOf("mimeType" to "image/jpeg", "data" to img)))
            }
            contents.add(mapOf("role" to "user", "parts" to userParts))
            contents.add(mapOf("role" to "model", "parts" to listOf(mapOf("text" to i.assistantText))))
        }
        return contents
    }
}
