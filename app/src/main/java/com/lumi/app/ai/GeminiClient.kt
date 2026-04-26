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
        .readTimeout(120, TimeUnit.SECONDS) // longer for streaming
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    companion object {
        // Longest marker is 18 chars; hold back 20 so a partial marker cannot leak through
        private const val MARKER_HOLDBACK = 20
    }

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
        temperature: Double = 0.7,
        /** When non-null, request a streaming response and emit visible-text deltas (excluding action JSON). */
        onVisibleChunk: ((String) -> Unit)? = null
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

        val streaming = onVisibleChunk != null
        val requestMap = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to messages,
            "temperature" to temperature,
            "max_tokens" to 2048
        )
        if (streaming) requestMap["stream"] = true

        val url = "$baseUrl/chat/completions"
        val body = gson.toJson(requestMap).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://github.com/skimosk/lumi")
            .addHeader("X-Title", "Lumi")
            .apply { if (streaming) addHeader("Accept", "text/event-stream") }
            .post(body)
            .build()

        if (streaming) {
            return@withContext readStream(request, model, onVisibleChunk!!)
        }

        http.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: throw Exception("Empty response body")
            if (!response.isSuccessful) throw Exception("OpenRouter ${response.code}: ${parseError(bodyStr)}")
            parseResponse(bodyStr, model)
        }
    }

    /**
     * Reads a Server-Sent-Events stream from OpenRouter and emits user-visible
     * deltas (text *before* the first action / data-request marker) via [onVisibleChunk].
     * Returns the full accumulated response — including any action JSON tail —
     * so the caller can still parse it the same way as a non-streaming response.
     */
    private fun readStream(
        request: Request,
        model: String,
        onVisibleChunk: (String) -> Unit
    ): GeminiResponse {
        val full = StringBuilder()
        var emittedUpTo = 0           // index in `full` already pushed to UI
        var stoppedEmitting = false   // true once we hit a marker; tail is action JSON
        var promptTokens = 0
        var outputTokens = 0

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                throw Exception("OpenRouter ${response.code}: ${parseError(errBody)}")
            }
            val source = response.body?.source() ?: throw Exception("Empty stream body")
            try {
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue

                    val delta = try {
                        val obj = JsonParser.parseString(payload).asJsonObject
                        obj["usage"]?.asJsonObject?.let {
                            promptTokens = it["prompt_tokens"]?.asInt ?: promptTokens
                            outputTokens = it["completion_tokens"]?.asInt ?: outputTokens
                        }
                        obj["choices"]?.asJsonArray?.get(0)?.asJsonObject
                            ?.get("delta")?.asJsonObject
                            ?.get("content")?.takeIf { !it.isJsonNull }?.asString
                    } catch (_: Exception) { null } ?: continue

                    full.append(delta)

                    if (!stoppedEmitting) {
                        val markerIdx = findMarkerStart(full)
                        val visibleEnd = if (markerIdx >= 0) {
                            stoppedEmitting = true
                            markerIdx
                        } else {
                            // Hold back enough characters that a partial marker can't slip out.
                            (full.length - MARKER_HOLDBACK).coerceAtLeast(emittedUpTo)
                        }
                        if (visibleEnd > emittedUpTo) {
                            onVisibleChunk(full.substring(emittedUpTo, visibleEnd))
                            emittedUpTo = visibleEnd
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                // Network blip mid-stream — keep whatever we already accumulated so the user
                // sees a partial answer instead of a hard error. The action JSON tail
                // (if any) is parsed downstream from `full.toString()`.
                if (full.isEmpty()) throw e
            }
        }
        // If the stream ended cleanly without ever hitting a marker, flush whatever's left.
        if (!stoppedEmitting && emittedUpTo < full.length) {
            onVisibleChunk(full.substring(emittedUpTo, full.length))
        }
        return GeminiResponse(full.toString(), model, promptTokens, outputTokens)
    }

    /** Returns the start index of the earliest action / data-request marker, or -1. */
    private fun findMarkerStart(buf: StringBuilder): Int {
        val a = buf.indexOf("___LUMI_ACTIONS___")
        val r = buf.indexOf("___LUMI_REQUEST___")
        return when {
            a < 0 -> r
            r < 0 -> a
            else -> minOf(a, r)
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

    /** Sends up to [batchSize] images with a description prompt to [model] (Haiku).
     *  Returns 0-based indices of images that match the description. */
    suspend fun checkImagesMatch(
        images: List<String>,
        description: String,
        model: String,
        metadata: List<String>? = null
    ): List<Int> = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext emptyList()
        val validPairs = images.mapIndexed { i, b64 -> i to b64 }.filter { isValidImageBase64(it.second) }
        if (validPairs.isEmpty()) return@withContext emptyList()

        val currentContent = mutableListOf<Map<String, Any>>()
        
        val promptText = StringBuilder("Images numbered 1 to ${validPairs.size}. Which match: \"$description\"?\n")
        if (metadata != null) {
            promptText.append("Metadata for each image:\n")
            validPairs.forEach { (originalIdx, _) ->
                if (originalIdx < metadata.size) {
                    promptText.append("Image ${validPairs.indexOfFirst { it.first == originalIdx } + 1}: ${metadata[originalIdx]}\n")
                }
            }
        }
        promptText.append("Reply ONLY with a JSON array of matching numbers e.g. [1,3] or [] if none. No other text.")

        currentContent.add(mapOf("type" to "text", "text" to promptText.toString()))
        validPairs.forEach { (_, b64) ->
            val mime = if (b64.startsWith("iVBOR")) "image/png" else "image/jpeg"
            currentContent.add(mapOf("type" to "image_url",
                "image_url" to mapOf("url" to "data:$mime;base64,$b64")))
        }

        val requestMap = mapOf(
            "model" to model,
            "messages" to listOf(mapOf("role" to "user", "content" to currentContent)),
            "temperature" to 0.0,
            "max_tokens" to 60
        )
        val url = "$baseUrl/chat/completions"
        val body = gson.toJson(requestMap).toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://github.com/skimosk/lumi")
            .addHeader("X-Title", "Lumi")
            .post(body).build()
        try {
            http.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val text = parseResponse(response.body?.string() ?: return@withContext emptyList(), model).text.trim()
                val arr = try { com.google.gson.JsonParser.parseString(text).asJsonArray } catch (_: Exception) { return@withContext emptyList() }
                arr.mapNotNull { try { it.asInt } catch (_: Exception) { null } }
                    .filter { it in 1..validPairs.size }
                    .map { validPairs[it - 1].first }  // convert to original 0-based index
            }
        } catch (_: Exception) { emptyList() }
    }
}
