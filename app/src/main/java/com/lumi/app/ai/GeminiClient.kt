package com.lumi.app.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiClient(private var apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    fun updateApiKey(key: String) { apiKey = key }

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
            "generationConfig" to mapOf(
                "temperature" to temperature,
                "maxOutputTokens" to 2048
            )
        )

        systemInstruction?.let {
            requestMap["systemInstruction"] = mapOf(
                "parts" to listOf(mapOf("text" to it))
            )
        }

        val url = "$BASE_URL/$model:generateContent?key=$apiKey"
        val body = gson.toJson(requestMap).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: throw Exception("Empty response body")
            if (!response.isSuccessful) {
                val errMsg = tryParseError(bodyStr)
                throw Exception("Gemini API error ${response.code}: $errMsg")
            }
            parseResponse(bodyStr, model)
        }
    }

    private fun parseResponse(json: String, model: String): GeminiResponse {
        val obj = JsonParser.parseString(json).asJsonObject
        val text = obj["candidates"]
            ?.asJsonArray?.get(0)?.asJsonObject
            ?.get("content")?.asJsonObject
            ?.get("parts")?.asJsonArray?.get(0)?.asJsonObject
            ?.get("text")?.asString ?: "No response from Gemini."

        val usage = obj["usageMetadata"]?.asJsonObject
        val promptTokens = usage?.get("promptTokenCount")?.asInt ?: 0
        val outputTokens = usage?.get("candidatesTokenCount")?.asInt ?: 0

        return GeminiResponse(text, model, promptTokens, outputTokens)
    }

    private fun tryParseError(json: String): String {
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            obj["error"]?.asJsonObject?.get("message")?.asString ?: json.take(200)
        } catch (e: Exception) {
            json.take(200)
        }
    }
}
