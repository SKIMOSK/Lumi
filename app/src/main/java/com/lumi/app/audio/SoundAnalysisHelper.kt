package com.lumi.app.audio

import com.lumi.app.ai.GeminiClient
import com.lumi.app.ai.GeminiResponse

/** Sends a WAV recording to the AI and returns a sound identification response. */
class SoundAnalysisHelper(private val client: GeminiClient) {

    suspend fun identify(
        wavBase64: String,
        userQuestion: String,
        model: String,
        temperature: Double = 0.3
    ): GeminiResponse =
        client.generate(
            prompt = userQuestion.ifBlank { "What sound is this? Identify it precisely and concisely." },
            audioBase64 = wavBase64,
            audioMimeType = "audio/wav",
            model = model,
            temperature = temperature
        )
}
