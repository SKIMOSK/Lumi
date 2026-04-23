package com.lumi.app.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class GeminiResponse(
    val text: String,
    val model: String,
    val promptTokens: Int = 0,
    val outputTokens: Int = 0
)

/** OpenRouter-only AI client (OpenAI-compatible API). */
class GeminiClient(
    private var apiKey: String,
    private var baseUrl: String = "https://openrouter.ai/api/v1"
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun updateConfig(key: String, url: String = "https://openrouter.ai/api/v1") {
        apiKey = key
        baseUrl = url
    }

    suspend fun generate(
        prompt: String,
        imageBase64: String? = null,
        model: String,
        history: List<Map<String, Any>> = emptyList(),
        systemInstruction: String? = null,
        temperature: Double = 0.7
    ): GeminiResponse = withContext(Dispatchers.IO) {

        val messages = mutableListOf<Map<String, Any>>()

        systemInstruction?.let {
            messages.add(mapOf("role" to "system", "content" to it))
        }

        // Convert Gemini-format history to OpenAI messages
        for (turn in history) {
            val role = if (turn["role"] == "model") "assistant" else "user"
            @Suppress("UNCHECKED_CAST")
            val parts = turn["parts"] as? List<Map<String, Any>> ?: continue
            messages.add(mapOf("role" to role, "content" to geminiPartsToContent(parts)))
        }

        // Current user turn
        val currentContent = mutableListOf<Map<String, Any>>()
        currentContent.add(mapOf("type" to "text", "text" to prompt))
        // Only include image if it passes basic validity checks
        imageBase64?.takeIf { isValidImageBase64(it) }?.let { img ->
            val mimeType = if (img.startsWith("iVBOR")) "image/png" else "image/jpeg"
            currentContent.add(mapOf(
                "type" to "image_url",
                "image_url" to mapOf("url" to "data:$mimeType;base64,$img")
            ))
        }
        messages.add(mapOf("role" to "user", "content" to currentContent))

        val requestMap = mapOf(
            "model" to model,
            "messages" to messages,
            "temperature" to temperature,
            "max_tokens" to 2048
        )

        val url = "$baseUrl/chat/completions"
        val body = gson.toJson(requestMap).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://github.com/skimosk/lumi")
            .addHeader("X-Title", "Lumi")
            .post(body)
            .build()

        http.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: throw Exception("Empty response body")
            if (!response.isSuccessful) throw Exception("OpenRouter ${response.code}: ${parseError(bodyStr)}")
            parseResponse(bodyStr, model)
        }
    }

    private fun geminiPartsToContent(parts: List<Map<String, Any>>): Any {
        if (parts.size == 1 && parts[0].containsKey("text")) return parts[0]["text"] as? String ?: ""
        val content = mutableListOf<Map<String, Any>>()
        for (part in parts) {
            when {
                part.containsKey("text") ->
                    content.add(mapOf("type" to "text", "text" to (part["text"] as? String ?: "")))
                part.containsKey("inlineData") -> {
                    @Suppress("UNCHECKED_CAST")
                    val inline = part["inlineData"] as? Map<String, String> ?: continue
                    val data = inline["data"] ?: continue
                    content.add(mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/jpeg;base64,$data")))
                }
            }
        }
        return content
    }

    private fun parseResponse(json: String, model: String): GeminiResponse {
        val obj = JsonParser.parseString(json).asJsonObject
        val text = obj["choices"]
            ?.asJsonArray?.get(0)?.asJsonObject
            ?.get("message")?.asJsonObject
            ?.get("content")?.asString ?: "No response."
        val usage = obj["usage"]?.asJsonObject
        return GeminiResponse(
            text, model,
            usage?.get("prompt_tokens")?.asInt ?: 0,
            usage?.get("completion_tokens")?.asInt ?: 0
        )
    }

    /** JPEG base64 starts with /9j/, PNG with iVBOR. Rejects empty or corrupted data. */
    private fun isValidImageBase64(b64: String): Boolean {
        if (b64.isBlank() || b64.length < 32) return false
        val head = b64.trimStart().take(8)
        return head.startsWith("/9j/") || head.startsWith("iVBOR")
    }

    private fun parseError(json: String): String = try {
        val obj = JsonParser.parseString(json).asJsonObject
        obj["error"]?.asJsonObject?.get("message")?.asString
            ?: obj["error"]?.asString
            ?: json.take(300)
    } catch (e: Exception) { json.take(300) }
}
