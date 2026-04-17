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

Regula de baza: Nu inventa informatii din telefon. Daca ai nevoie de date (notificari, contacte,
mesaje WhatsApp, timere), CERE-LE explicit la SFARSITUL raspunsului tau, INAINTE de a raspunde,
folosind formatul de mai jos. Vei primi datele si vei putea raspunde complet.
Nu folosi simboluri markdown (asteriscuri, diez, liniute de lista). Raspunde natural, ca si cand vorbesti.

Cerere date (adauga LA FINAL daca ai nevoie):
___LUMI_REQUEST___
{"notifications":true,"contacts":["numeContact"],"whatsapp":{"contact":"numeContact","limit":25},"timers":true}

Include NUMAI campurile necesare. Nu adauga nimic dupa ___LUMI_REQUEST___.
""".trimIndent()

    private val noActionModeNote = """

IMPORTANT: Nu poti executa actiuni (timere, mesaje, apeluri). Daca utilizatorul cere o astfel de actiune, spune-i sa activeze "Mod Actiune" din Setari.
""".trimIndent()

    private val actionSystemPromptAddition = """

Modul Actiune ACTIVAT. Poti executa actiuni reale. Raspunde utilizatorului, apoi adauga EXACT la final:
___LUMI_ACTIONS___
{"actions":[...]}

Exemple JSON corecte:
Timer 5 min: {"actions":[{"type":"SET_TIMER","name":"Paste","duration_seconds":"300"}]}
Alarma: {"actions":[{"type":"SET_ALARM","name":"Dimineata","time_24h":"07:30"}]}
Cronometru: {"actions":[{"type":"SET_STOPWATCH","name":"Alergare"}]}
Pauza timer: {"actions":[{"type":"PAUSE_TIMER","name":"Paste"}]}
WhatsApp: {"actions":[{"type":"SEND_WHATSAPP","contact":"Mama","message":"Vin acasa","exact":"true"}]}
SMS: {"actions":[{"type":"SEND_SMS","contact":"Ana","message":"Salut"}]}
Apel: {"actions":[{"type":"CALL","contact":"Tata"}]}

REGULI: duration_seconds TREBUIE sa fie string cu numar intreg (ex: "300" nu 300). exact="false" daca mesajul nu e citat mot-a-mot de utilizator.
""".trimIndent()

    private val classifyPrompt = """
Clasifica cererea de mai jos ca SIMPLU sau COMPLEX.
SIMPLU: raspunsuri rapide, identificare obiecte, citire notificari, calcule, traduceri, timere simple.
COMPLEX: trimitere mesaje, apeluri, cautare contacte, orchestrare multi-pas, acces WhatsApp.
Raspunde cu UN SINGUR CUVANT: SIMPLU sau COMPLEX
""".trimIndent()

    // ─── Main entry ──────────────────────────────────────────────────────────

    suspend fun route(
        userPrompt: String,
        imageBase64: String?,
        memory: ConversationMemory,
        fastWordLimit: Int = 45,
        expertWordLimit: Int = 95,
        forceExpert: Boolean = false
    ): RouteResult {
        val history = memory.toGeminiContents()
        val fastModel = settings.fastModel

        // 1. Classify
        val cls = client.generate(
            prompt = "$classifyPrompt\n\nCerere: \"$userPrompt\"",
            model = fastModel, temperature = 0.0
        ).text.trim().uppercase()
        val useExpert = forceExpert || cls.contains("COMPLEX")
        val model = if (useExpert) settings.expertModel else fastModel
        val wordLimit = if (useExpert) expertWordLimit else fastWordLimit

        val actionNote = if (settings.actionModeEnabled) actionSystemPromptAddition else noActionModeNote
        val sysPrompt = baseSystemPrompt +
            "\n\nIMPORTANT: Raspunde in cel mult $wordLimit cuvinte." +
            actionNote

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

        // 4. Strip markdown from display text
        val cleanParsed = parsed.copy(displayText = stripMarkdown(parsed.displayText))

        // 5. Execute actions if action mode ON
        val actionResults = if (settings.actionModeEnabled && cleanParsed.actions.isNotEmpty()) {
            executor?.executeAll(cleanParsed.actions) ?: emptyList()
        } else emptyList()

        return RouteResult(firstResponse, cleanParsed, useExpert, actionResults)
    }

    // ─── Markdown stripper (responses go to TTS) ─────────────────────────────

    private fun stripMarkdown(text: String): String = text
        .replace(Regex("(?m)^#{1,6}\\s+"), "")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("\\*(.+?)\\*"), "$1")
        .replace(Regex("_(.+?)_"), "$1")
        .replace(Regex("`(.+?)`"), "$1")
        .replace(Regex("(?m)^[-*•]\\s+"), "")
        .replace(Regex("(?m)^\\d+\\.\\s+"), "")
        .replace(Regex("(?m)^---+$"), "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    // ─── Data fulfillment ────────────────────────────────────────────────────

    private fun buildDataContext(req: com.lumi.app.actions.DataRequest): String {
        val sb = StringBuilder()

        if (req.notifications) {
            val n = LumiNotificationService.formatSummary(20)
            sb.appendLine("=== Notificari recente ===")
            sb.appendLine(if (n.isBlank()) "Fara notificari." else n)
        }

        if (req.contacts.isNotEmpty()) {
            sb.appendLine("=== Contacte ===")
            req.contacts.forEach { name ->
                val found = contacts.findBestMatch(name)
                if (found != null) {
                    sb.appendLine("$name → ${found.name} (${found.phoneNumbers.joinToString()})")
                } else {
                    sb.appendLine("$name → negasit")
                }
            }
        }

        req.whatsapp?.let { wa ->
            sb.appendLine("=== Mesaje WhatsApp cu ${wa.contact} (ultimele ${wa.limit}) ===")
            if (LumiAccessibilityService.isAvailable()) {
                val msgs = LumiAccessibilityService.readVisibleMessages(wa.limit)
                if (msgs.isEmpty()) sb.appendLine("Niciun mesaj vizibil. Deschide conversatia in WhatsApp.")
                else msgs.forEach { sb.appendLine(it) }
            } else {
                val notifs = LumiNotificationService.getFromApp("com.whatsapp", wa.limit)
                if (notifs.isEmpty()) sb.appendLine("Nu am acces la WhatsApp. Activeaza Accessibility Service.")
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
