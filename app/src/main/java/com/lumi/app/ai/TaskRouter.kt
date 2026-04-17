package com.lumi.app.ai

import com.lumi.app.actions.ActionExecutor
import com.lumi.app.actions.ActionParser
import com.lumi.app.actions.ParsedAIResponse
import com.lumi.app.contacts.ContactsHelper
import com.lumi.app.notifications.LumiNotificationService
import com.lumi.app.settings.AppSettings
import com.lumi.app.timer.TimerManager
import com.lumi.app.whatsapp.LumiAccessibilityService

class TaskRouter(
    private val client: GeminiClient,
    private val settings: AppSettings,
    private val timerManager: TimerManager,
    private val contacts: ContactsHelper,
    private val executor: ActionExecutor?  // null when action mode is OFF
) {
    data class RouteResult(
        val response: GeminiResponse,
        val parsed: ParsedAIResponse,
        val usedExpert: Boolean,
        val actionResults: List<ActionExecutor.Result> = emptyList()
    )

    // ─── System prompts ──────────────────────────────────────────────────────

    private val baseSystemPrompt get() = """
${settings.systemPrompt}

## Regula de bază
Nu inventa informații din telefon. Dacă ai nevoie de date (notificări, contacte, mesaje WhatsApp,
timere), CERE-LE explicit la SFÂRȘITUL răspunsului tău, ÎNAINTE de a răspunde la întrebare,
folosind formatul de mai jos. Vei primi datele cerute și vei putea răspunde complet.

## Cerere date (adaugă LA FINAL dacă ai nevoie)
___LUMI_REQUEST___
{"notifications":true,"contacts":["numeContact"],"whatsapp":{"contact":"numeContact","limit":25},"timers":true}

Include NUMAI câmpurile necesare. Nu adăuga nimic după ___LUMI_REQUEST___.
""".trimIndent()

    private val actionSystemPromptAddition = """

## Modul Acțiune ACTIVAT
Poți executa acțiuni reale. Adaugă LA FINAL (după textul tău):
___LUMI_ACTIONS___
{"actions":[{"type":"TIP","param":"valoare"}]}

Tipuri disponibile:
SET_TIMER(name, duration_seconds) — SET_STOPWATCH(name) — SET_ALARM(name, time_24h e.g. "07:30")
PAUSE_TIMER(name) — RESUME_TIMER(name) — CANCEL_TIMER(name) — RESET_TIMER(name)
SEND_WHATSAPP(contact, message, exact="true" dacă mesajul e citat exact / "false" dacă îl compui tu)
SEND_SMS(contact, message) — CALL(contact)

Dacă mesajul NU e citat exact de la utilizator, pune exact="false" și eu îl voi citi înainte de trimitere.
""".trimIndent()

    private val classifyPrompt = """
Clasifică cererea de mai jos ca SIMPLU sau COMPLEX.
SIMPLU: răspunsuri rapide, identificare obiecte, citire notificări, calcule, traduceri, timere simple.
COMPLEX: trimitere mesaje, apeluri, căutare contacte, orchestrare multi-pas, acces WhatsApp.
Răspunde cu UN SINGUR CUVÂNT: SIMPLU sau COMPLEX
""".trimIndent()

    // ─── Main entry ──────────────────────────────────────────────────────────

    suspend fun route(
        userPrompt: String,
        imageBase64: String?,
        memory: ConversationMemory
    ): RouteResult {
        val history = memory.toGeminiContents()
        val fastModel = settings.fastModel
        val sysPrompt = baseSystemPrompt + if (settings.actionModeEnabled) actionSystemPromptAddition else ""

        // 1. Classify
        val cls = client.generate(
            prompt = "$classifyPrompt\n\nCerere: \"$userPrompt\"",
            model = fastModel, temperature = 0.0
        ).text.trim().uppercase()
        val useExpert = cls.contains("COMPLEX")
        val model = if (useExpert) settings.expertModel else fastModel

        // 2. First AI pass (may return a data request)
        var firstResponse = client.generate(
            prompt = userPrompt, imageBase64 = imageBase64,
            model = model, history = history, systemInstruction = sysPrompt
        )
        var parsed = ActionParser.parse(firstResponse.text)

        // 3. If AI requested data, fulfill and re-query
        if (parsed.dataRequest != null) {
            val dataContext = buildDataContext(parsed.dataRequest!!)
            val enrichedPrompt = "$userPrompt\n\n[Datele cerute:]\n$dataContext"
            firstResponse = client.generate(
                prompt = enrichedPrompt, imageBase64 = imageBase64,
                model = model, history = history, systemInstruction = sysPrompt
            )
            parsed = ActionParser.parse(firstResponse.text)
        }

        // 4. Execute actions if action mode ON
        val actionResults = if (settings.actionModeEnabled && parsed.actions.isNotEmpty()) {
            executor?.executeAll(parsed.actions) ?: emptyList()
        } else emptyList()

        return RouteResult(firstResponse, parsed, useExpert, actionResults)
    }

    // ─── Data fulfillment ────────────────────────────────────────────────────

    private fun buildDataContext(req: com.lumi.app.actions.DataRequest): String {
        val sb = StringBuilder()

        if (req.notifications) {
            val n = LumiNotificationService.formatSummary(20)
            sb.appendLine("=== Notificări recente ===")
            sb.appendLine(if (n.isBlank()) "Fără notificări." else n)
        }

        if (req.contacts.isNotEmpty()) {
            sb.appendLine("=== Contacte ===")
            req.contacts.forEach { name ->
                val found = contacts.resolveAlias(name) ?: contacts.searchByName(name).firstOrNull()
                if (found != null) {
                    sb.appendLine("$name → ${found.name} (${found.phoneNumbers.joinToString()})")
                } else {
                    sb.appendLine("$name → negăsit")
                }
            }
        }

        req.whatsapp?.let { wa ->
            sb.appendLine("=== Mesaje WhatsApp cu ${wa.contact} (ultimele ${wa.limit}) ===")
            if (LumiAccessibilityService.isAvailable()) {
                val msgs = LumiAccessibilityService.readVisibleMessages(wa.limit)
                if (msgs.isEmpty()) sb.appendLine("Niciun mesaj vizibil. Deschide conversația în WhatsApp.")
                else msgs.forEach { sb.appendLine(it) }
            } else {
                // Fallback: use notification history
                val notifs = LumiNotificationService.getFromApp("com.whatsapp", wa.limit)
                if (notifs.isEmpty()) sb.appendLine("Nu am acces la WhatsApp. Activează Accessibility Service.")
                else notifs.forEach { sb.appendLine(it.formatted()) }
            }
        }

        if (req.timers) {
            sb.appendLine("=== Timere active ===")
            sb.appendLine(timerManager.formatStatus())
        }

        return sb.toString().trim()
    }
}
