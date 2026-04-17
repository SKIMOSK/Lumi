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

enum class ApiProvider { GEMINI_DIRECT, OPEN_ROUTER }

class GeminiClient(
    private var apiKey: String,
    private var provider: ApiProvider = ApiProvider.GEMINI_DIRECT,
    private var openRouterBaseUrl: String = "https://openrouter.ai/api/v1"
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun updateConfig(key: String, prov: ApiProvider, baseUrl: String = "https://openrouter.ai/api/v1") {
        apiKey = key
        provider = prov
        openRouterBaseUrl = baseUrl
    }

    data class GeminiResponse(
        val text: String,
        val model: String,
        val promptTokens: Int = 0,
        val outputTokens: Int = 0
    )

    suspend fun generate(
        prompt: String,
        imageBase64: String? = null,
        model: String,
        history: List<Map<String, Any>> = emptyList(),
        systemInstruction: String? = null,
        temperature: Double = 0.7
    ): GeminiResponse = withContext(Dispatchers.IO) {
        when (provider) {
            ApiProvider.GEMINI_DIRECT -> generateGemini(prompt, imageBase64, model, history, systemInstruction, temperature)
            ApiProvider.OPEN_ROUTER -> generateOpenRouter(prompt, imageBase64, model, history, systemInstruction, temperature)
        }
    }

    // ─── Gemini Direct ───────────────────────────────────────────────────────

    private fun generateGemini(
        prompt: String,
        imageBase64: String?,
        model: String,
        history: List<Map<String, Any>>,
        systemInstruction: String?,
        temperature: Double
    ): GeminiResponse {
        val currentParts = mutableListOf<Map<String, Any>>()
        currentParts.add(mapOf("text" to prompt))
        imageBase64?.let { img ->
            currentParts.add(mapOf("inlineData" to mapOf(
                "mimeType" to "image/jpeg",
                "data" to img
            )))
        }

        val contents = history.toMutableList()
        contents.add(mapOf("role" to "user", "parts" to currentParts))

        val requestMap = mutableMapOf<String, Any>(
            "contents" to contents,
            "generationConfig" to mapOf("temperature" to temperature, "maxOutputTokens" to 2048)
        )
        systemInstruction?.let {
            requestMap["systemInstruction"] = mapOf("parts" to listOf(mapOf("text" to it)))
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val body = gson.toJson(requestMap).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        http.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: throw Exception("Empty response body")
            if (!response.isSuccessful) throw Exception("Gemini API ${response.code}: ${parseError(bodyStr)}")
            return parseGeminiResponse(bodyStr, model)
        }
    }

    private fun parseGeminiResponse(json: String, model: String): GeminiResponse {
        val obj = JsonParser.parseString(json).asJsonObject
        val text = obj["candidates"]
            ?.asJsonArray?.get(0)?.asJsonObject
            ?.get("content")?.asJsonObject
            ?.get("parts")?.asJsonArray?.get(0)?.asJsonObject
            ?.get("text")?.asString ?: "No response."
        val usage = obj["usageMetadata"]?.asJsonObject
        return GeminiResponse(
            text, model,
            usage?.get("promptTokenCount")?.asInt ?: 0,
            usage?.get("candidatesTokenCount")?.asInt ?: 0
        )
    }

    // ─── OpenRouter (OpenAI-compatible) ──────────────────────────────────────

    private fun generateOpenRouter(
        prompt: String,
        imageBase64: String?,
        model: String,
        history: List<Map<String, Any>>,
        systemInstruction: String?,
        temperature: Double
    ): GeminiResponse {
        val messages = mutableListOf<Map<String, Any>>()

        // System message
        systemInstruction?.let {
            messages.add(mapOf("role" to "system", "content" to it))
        }

        // Convert Gemini-format history (contents) → OpenAI messages
        for (turn in history) {
            val role = when (turn["role"] as? String) {
                "model" -> "assistant"
                else -> "user"
            }
            @Suppress("UNCHECKED_CAST")
            val parts = turn["parts"] as? List<Map<String, Any>> ?: continue
            val content = geminiPartsToOpenAIContent(parts)
            messages.add(mapOf("role" to role, "content" to content))
        }

        // Current user turn
        val currentContent = mutableListOf<Map<String, Any>>()
        currentContent.add(mapOf("type" to "text", "text" to prompt))
        imageBase64?.let { img ->
            currentContent.add(mapOf(
                "type" to "image_url",
                "image_url" to mapOf("url" to "data:image/jpeg;base64,$img")
            ))
        }
        messages.add(mapOf("role" to "user", "content" to currentContent))

        val orModel = toOpenRouterModelId(model)
        val requestMap = mapOf(
            "model" to orModel,
            "messages" to messages,
            "temperature" to temperature,
            "max_tokens" to 2048
        )

        val url = "$openRouterBaseUrl/chat/completions"
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
            return parseOpenRouterResponse(bodyStr, orModel)
        }
    }

    /** Convert Gemini parts list to OpenAI content (string or array). */
    private fun geminiPartsToOpenAIContent(parts: List<Map<String, Any>>): Any {
        if (parts.size == 1 && parts[0].containsKey("text")) {
            return parts[0]["text"] as? String ?: ""
        }
        val content = mutableListOf<Map<String, Any>>()
        for (part in parts) {
            when {
                part.containsKey("text") -> content.add(mapOf("type" to "text", "text" to (part["text"] as? String ?: "")))
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

    private fun parseOpenRouterResponse(json: String, model: String): GeminiResponse {
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

    /** Map internal Gemini model IDs to OpenRouter model IDs. */
    private fun toOpenRouterModelId(model: String): String = when (model) {
        "gemini-1.5-flash"                    -> "google/gemini-flash-1.5"
        "gemini-2.5-flash-preview-04-17"      -> "google/gemini-2.5-flash-preview-04-17"
        "gemini-2.5-pro-preview-03-25"        -> "google/gemini-2.5-pro-preview-03-25"
        else -> model  // pass through if already an OpenRouter-style ID
    }

    // ─── Shared ──────────────────────────────────────────────────────────────

    private fun parseError(json: String): String = try {
        val obj = JsonParser.parseString(json).asJsonObject
        obj["error"]?.asJsonObject?.get("message")?.asString
            ?: obj["error"]?.asString
            ?: json.take(300)
    } catch (e: Exception) { json.take(300) }
}
