package com.lumi.app.ai

import com.lumi.app.settings.AppSettings

/**
 * Routes each request to Flash (simple/fast) or Pro (complex/orchestration).
 *
 * Classification prompt asks Gemini Flash to tag the task.
 * Complex tasks that need multi-step phone actions are forwarded to the Pro model.
 */
class TaskRouter(
    private val client: GeminiClient,
    private val settings: AppSettings
) {

    data class RouteResult(
        val response: GeminiResponse,
        val usedPro: Boolean,
        val classification: TaskClass
    )

    enum class TaskClass { SIMPLE, COMPLEX }

    private val classifyPrompt = """
Classify the user request below as SIMPLE or COMPLEX.

SIMPLE: Quick answers, object/scene identification, reading aloud a notification or message summary,
simple calculations, weather, time, translations.

COMPLEX: Sending messages on behalf of the user, searching contacts, multi-step actions across apps,
writing in a specific personal style, accessing WhatsApp/other apps, scheduling, or anything
requiring orchestration across multiple information sources.

Reply with exactly one word: SIMPLE or COMPLEX
""".trimIndent()

    private val systemPrompt get() = settings.systemPrompt

    suspend fun route(
        userPrompt: String,
        imageBase64: String?,
        memory: ConversationMemory,
        notificationContext: String? = null
    ): RouteResult {

        val enrichedPrompt = buildEnrichedPrompt(userPrompt, notificationContext)
        val history = memory.toGeminiContents()
        val fastModel = settings.fastModel

        // Ask Flash to classify — cheap and fast
        val classificationResponse = client.generate(
            prompt = "$classifyPrompt\n\nUser request: \"$userPrompt\"",
            model = fastModel,
            temperature = 0.0
        )
        val taskClass = if (classificationResponse.text.trim().uppercase().contains("COMPLEX"))
            TaskClass.COMPLEX else TaskClass.SIMPLE

        return if (taskClass == TaskClass.COMPLEX) {
            val proResponse = client.generate(
                prompt = enrichedPrompt,
                imageBase64 = imageBase64,
                model = settings.proModel,
                history = history,
                systemInstruction = systemPrompt
            )
            RouteResult(proResponse, usedPro = true, classification = TaskClass.COMPLEX)
        } else {
            val flashResponse = client.generate(
                prompt = enrichedPrompt,
                imageBase64 = imageBase64,
                model = fastModel,
                history = history,
                systemInstruction = systemPrompt
            )
            RouteResult(flashResponse, usedPro = false, classification = TaskClass.SIMPLE)
        }
    }

    private fun buildEnrichedPrompt(userPrompt: String, notificationContext: String?): String {
        if (notificationContext.isNullOrBlank()) return userPrompt
        return """$userPrompt

[Context - Recent phone notifications/messages:]
$notificationContext"""
    }
}
