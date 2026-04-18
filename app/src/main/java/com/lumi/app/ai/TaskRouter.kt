package com.lumi.app.ai

import android.os.Build
import com.lumi.app.actions.ActionExecutor
import com.lumi.app.actions.ActionParser
import com.lumi.app.contacts.ContactsHelper
import com.lumi.app.notes.NotesHelper
import com.lumi.app.notifications.LumiNotificationService
import com.lumi.app.settings.AppSettings
import com.lumi.app.system.SystemSettingsHelper
import com.lumi.app.timer.TimerManager
import com.lumi.app.whatsapp.LumiAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaskRouter(
    private val client: GeminiClient,
    private val settings: AppSettings,
    private val timerManager: TimerManager,
    private val contacts: ContactsHelper,
    private val notes: NotesHelper,
    private val sysSettings: SystemSettingsHelper,
    private val executor: ActionExecutor?
) {
    data class RouteResult(
        val response: GeminiResponse,
        val parsed: com.lumi.app.actions.ParsedAIResponse,
        val usedExpert: Boolean,
        val actionResults: List<ActionExecutor.Result> = emptyList()
    )

    // ─── Context injection ───────────────────────────────────────────────────

    private val deviceContext get(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        val model = "${Build.MANUFACTURER} ${Build.MODEL}"
        val brightness = sysSettings.getBrightness()
        val volumeMedia = sysSettings.getVolume("media")
        val volumeRing  = sysSettings.getVolume("ring")
        val dnd = if (sysSettings.isDNDEnabled()) "activat" else "dezactivat"
        return """
Data si ora: $now
Dispozitiv: $model
Luminozitate: $brightness% | Volum media: $volumeMedia% | Volum sonerie: $volumeRing% | Nu deranjati: $dnd
""".trimIndent()
    }

    // ─── System prompts ──────────────────────────────────────────────────────

    private val baseSystemPrompt get() = """
${settings.systemPrompt}

$deviceContext

Regula: Nu inventa informatii din telefon. Daca ai nevoie de date (notificari, contacte,
WhatsApp, timere, notite), CERE-LE la SFARSITUL raspunsului tau folosind formatul de mai jos.
Nu folosi simboluri markdown. Raspunde natural, ca si cand vorbesti.

Cerere date (adauga LA FINAL daca ai nevoie):
___LUMI_REQUEST___
{"notifications":true,"contacts":["numeContact"],"whatsapp":{"contact":"numeContact","limit":25},"timers":true,"notes":true,"notes_query":"cuvant cheie"}

Include NUMAI campurile necesare. Nu adauga nimic dupa ___LUMI_REQUEST___.
""".trimIndent()

    private val noActionModeNote get() = """

IMPORTANT: Nu poti executa actiuni (timere, mesaje, apeluri, setari). Daca utilizatorul cere o actiune, spune-i sa activeze "Mod Actiune" din Setari.
""".trimIndent()

    private val actionSystemPrompt get(): String {
        val autonomous = settings.autonomousMode
        val behavior = if (autonomous)
            "Mod COMPLET AUTONOM. Executa actiunile imediat, fara sa ceri confirmare. Intreaba numai daca informatia critica (ex: destinatar, continut mesaj) lipseste complet."
        else
            "Mod Actiune. Actiunile vor cere confirmare utilizatorului. Descrie ce vei face, apoi adauga blocul de actiuni."
        return """

$behavior
Dupa textul tau, adauga EXACT:
___LUMI_ACTIONS___
{"actions":[...]}

Exemple JSON:
Timer 5 min: {"actions":[{"type":"SET_TIMER","name":"Paste","duration_seconds":"300"}]}
Alarma: {"actions":[{"type":"SET_ALARM","name":"Dimineata","time_24h":"07:30"}]}
Cronometru: {"actions":[{"type":"SET_STOPWATCH","name":"Alergare"}]}
WhatsApp: {"actions":[{"type":"SEND_WHATSAPP","contact":"Mama","message":"Vin acasa","exact":"true"}]}
SMS: {"actions":[{"type":"SEND_SMS","contact":"Ana","message":"Salut"}]}
Apel: {"actions":[{"type":"CALL","contact":"Tata"}]}
Notita noua: {"actions":[{"type":"WRITE_NOTE","title":"Cumparaturi","content":"Lapte, oua, paine"}]}
Actualizeaza notita: {"actions":[{"type":"WRITE_NOTE","id":"ID_NOTITA","content":"Text nou"}]}
Luminozitate: {"actions":[{"type":"SET_BRIGHTNESS","level":"60"}]}
Volum: {"actions":[{"type":"SET_VOLUME","stream":"media","level":"50"}]}
Nu deranjati: {"actions":[{"type":"SET_DND","enabled":"true"}]}

REGULI: duration_seconds trebuie sa fie string intreg (ex: "300"). exact="false" daca mesajul nu e citat mot-a-mot.
""".trimIndent()
    }

    private val classifyPrompt = """
Clasifica cererea de mai jos ca SIMPLU sau COMPLEX.
SIMPLU: raspunsuri rapide, identificare obiecte, calcule, traduceri, timere, notite, setari sistem.
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

        val cls = client.generate(
            prompt = "$classifyPrompt\n\nCerere: \"$userPrompt\"",
            model = fastModel, temperature = 0.0
        ).text.trim().uppercase()
        val useExpert = forceExpert || cls.contains("COMPLEX")
        val model = if (useExpert) settings.expertModel else fastModel
        val wordLimit = if (useExpert) expertWordLimit else fastWordLimit

        val actionNote = if (settings.actionModeEnabled) actionSystemPrompt else noActionModeNote
        val sysPrompt = baseSystemPrompt +
            "\n\nIMPORTANT: Raspunde in cel mult $wordLimit cuvinte." +
            actionNote

        var firstResponse = client.generate(
            prompt = userPrompt, imageBase64 = imageBase64,
            model = model, history = history, systemInstruction = sysPrompt
        )
        var parsed = ActionParser.parse(firstResponse.text)

        if (parsed.dataRequest != null) {
            val dataContext = buildDataContext(parsed.dataRequest!!)
            val enrichedPrompt = "$userPrompt\n\n[Datele cerute:]\n$dataContext"
            firstResponse = client.generate(
                prompt = enrichedPrompt, imageBase64 = imageBase64,
                model = model, history = history, systemInstruction = sysPrompt
            )
            parsed = ActionParser.parse(firstResponse.text)
        }

        val cleanParsed = parsed.copy(displayText = stripMarkdown(parsed.displayText))

        val actionResults = if (settings.actionModeEnabled && cleanParsed.actions.isNotEmpty()) {
            executor?.executeAll(cleanParsed.actions) ?: emptyList()
        } else emptyList()

        return RouteResult(firstResponse, cleanParsed, useExpert, actionResults)
    }

    // ─── Markdown stripper ───────────────────────────────────────────────────

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
                if (found != null) sb.appendLine("$name → ${found.name} (${found.phoneNumbers.joinToString()})")
                else sb.appendLine("$name → negasit")
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

        if (req.notes) {
            sb.appendLine("=== Notite Lumi ===")
            val query = req.notes_query
            val noteText = if (query != null) {
                val found = notes.search(query)
                if (found.isEmpty()) "Nicio notita cu '$query'." else found.joinToString("\n---\n") { "${it.title}: ${it.content}" }
            } else notes.formatSummary()
            sb.appendLine(noteText)
            // Attempt Samsung Notes
            val samsungNotes = notes.readSamsungNotes(5)
            if (samsungNotes.isNotEmpty()) {
                sb.appendLine("=== Samsung Notes (recente) ===")
                samsungNotes.forEach { sb.appendLine(it) }
            }
        }

        return sb.toString().trim()
    }
}
