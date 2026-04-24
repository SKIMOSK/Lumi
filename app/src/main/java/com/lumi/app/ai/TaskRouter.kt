package com.lumi.app.ai

import android.os.Build
import com.lumi.app.actions.ActionExecutor
import com.lumi.app.actions.ActionParser
import com.lumi.app.bluetooth.BluetoothDeviceManager
import com.lumi.app.calendar.CalendarHelper
import com.lumi.app.contacts.ContactsHelper
import com.lumi.app.gallery.GallerySearchHelper
import com.lumi.app.notes.NotesHelper
import com.lumi.app.notes.UserMemory
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
    private val calendar: CalendarHelper,
    private val executor: ActionExecutor?,
    private val userMemory: UserMemory,
    private val btDeviceManager: BluetoothDeviceManager,
    private val gallerySearchHelper: GallerySearchHelper? = null
) {
    data class RouteResult(
        val response: GeminiResponse,
        val parsed: com.lumi.app.actions.ParsedAIResponse,
        val usedExpert: Boolean,
        val actionResults: List<ActionExecutor.Result> = emptyList(),
        val galleryImageIds: List<Long>? = null
    )

    private var lastGalleryIds: List<Long>? = null

    // ─── Context injection ───────────────────────────────────────────────────

    private val deviceContext get(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        val model = "${Build.MANUFACTURER} ${Build.MODEL}"
        val brightness = sysSettings.getBrightness()
        val volumeMedia = sysSettings.getVolume("media")
        val volumeRing  = sysSettings.getVolume("ring")
        val dnd = if (sysSettings.isDNDEnabled()) "activat" else "dezactivat"
        val batterySaver = if (sysSettings.isBatterySaverEnabled()) "activat" else "dezactivat"
        val btDevices = btDeviceManager.formatDeviceList()
        val mem = userMemory.load()
        val memSection = if (mem.isNotBlank()) "\n\nPreferinte utilizator memorate:\n$mem" else ""
        return """
Data si ora: $now
Dispozitiv: $model
Luminozitate: $brightness% | Volum media: $volumeMedia% | Volum sonerie: $volumeRing% | Nu deranjati: $dnd | Economisire baterie: $batterySaver
Bluetooth: $btDevices$memSection
""".trimIndent()
    }

    // ─── System prompts ──────────────────────────────────────────────────────

    private val baseSystemPrompt get() = """
${settings.systemPrompt}

$deviceContext

Regula: Nu inventa informatii din telefon. Daca ai nevoie de date (notificari, contacte,
WhatsApp, timere, notite, calendar, galerie foto), CERE-LE la SFARSITUL raspunsului tau folosind formatul de mai jos.
Nu folosi simboluri markdown. Raspunde natural, ca si cand vorbesti.

Cerere date (adauga LA FINAL daca ai nevoie):
___LUMI_REQUEST___
{"notifications":true,"contacts":["numeContact"],"whatsapp":{"contact":"numeContact","limit":25},"timers":true,"notes":true,"notes_query":"cuvant cheie","calendar":true,"gallery":{"query":"plaja","from_date":"2024-06-01","to_date":"2024-09-01","limit":10}}

Include NUMAI campurile necesare. Nu adauga nimic dupa ___LUMI_REQUEST___.

Galerie foto: "cea mai recenta poza", "ultima poza", "ce am fotografiat ultima data" → cauta direct fara a intreba. Pentru cautari cu descriere fara data → intreaba perioada. Cauta doar in top 1000 imagini recente. Poti cauta si dupa offset (ex: "a doua cea mai recenta" → limit:1, offset:1).
""".trimIndent()

    private val noActionModeNote get() = """

IMPORTANT: Nu poti executa actiuni (timere, mesaje, apeluri, navigare, VPN, calendar). Daca utilizatorul cere o actiune, spune-i sa activeze "Mod Actiune" din Setari.
""".trimIndent()

    private val actionSystemPrompt get(): String {
        val autonomous = settings.autonomousMode
        val behavior = if (autonomous)
            "Mod COMPLET AUTONOM. Executa actiunile imediat, fara sa ceri confirmare. Intreaba numai daca informatia critica (ex: destinatar, continut mesaj) lipseste complet."
        else
            "Mod Actiune. Actiunile vor ceri confirmare utilizatorului. Descrie ce vei face, apoi adauga blocul de actiuni."
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
Instagram: {"actions":[{"type":"SEND_INSTAGRAM","contact":"Ana","username":"ana.ig","message":"Buna"}]}
Snapchat: {"actions":[{"type":"SEND_SNAPCHAT","contact":"Ion","username":"ion_snap","message":"Salut"}]}
Facebook Messenger: {"actions":[{"type":"SEND_FACEBOOK","contact":"Maria","message":"Ce faci?"}]}
Discord: {"actions":[{"type":"SEND_DISCORD","contact":"Alex","username":"alex#1234","message":"Hey"}]}
Email: {"actions":[{"type":"SEND_EMAIL","to":"ana@gmail.com","subject":"Re: intalnire","body":"Ne vedem maine la 10."}]}
Citeste email: {"actions":[{"type":"READ_EMAIL"}]}
Apel: {"actions":[{"type":"CALL","contact":"Tata"}]}
Notita noua: {"actions":[{"type":"WRITE_NOTE","title":"Cumparaturi","content":"Lapte, oua, paine"}]}
Actualizeaza notita: {"actions":[{"type":"WRITE_NOTE","id":"ID_NOTITA","content":"Text nou"}]}
Google Maps: {"actions":[{"type":"NAVIGATE_MAPS","destination":"Piata Universitatii, Bucuresti"}]}
Waze: {"actions":[{"type":"NAVIGATE_WAZE","destination":"Aeroportul Henri Coanda"}]}
Surfshark conectare: {"actions":[{"type":"CONNECT_VPN","app":"surfshark","country":"Romania"}]}
NordVPN deconectare: {"actions":[{"type":"DISCONNECT_VPN","app":"nordvpn"}]}
Event calendar: {"actions":[{"type":"CREATE_EVENT","title":"Intalnire","description":"Discutie proiect","location":"Birou","start_datetime":"2024-01-15T14:00","end_datetime":"2024-01-15T15:00"}]}
Calendar: {"actions":[{"type":"READ_CALENDAR"}]}
Luminozitate: {"actions":[{"type":"SET_BRIGHTNESS","level":"60"}]}
Volum: {"actions":[{"type":"SET_VOLUME","stream":"media","level":"50"}]}
Nu deranjati: {"actions":[{"type":"SET_DND","enabled":"true"}]}
Spotify play/pauza: {"actions":[{"type":"MEDIA_CONTROL","app":"spotify","command":"play_pause"}]}
Spotify urmatoarea: {"actions":[{"type":"MEDIA_CONTROL","app":"spotify","command":"next"}]}
Spotify cauta: {"actions":[{"type":"MEDIA_CONTROL","app":"spotify","command":"search","query":"Coldplay"}]}
YouTube Music: {"actions":[{"type":"MEDIA_CONTROL","app":"youtube_music","command":"open","query":"Lo-fi chill"}]}
Netflix: {"actions":[{"type":"MEDIA_CONTROL","app":"netflix","command":"open","query":"Stranger Things"}]}
Google Home: {"actions":[{"type":"HOME_CONTROL","app":"google_home","device":"Becuri living","action":"turn off"}]}
Sanatate (Google Fit): {"actions":[{"type":"READ_HEALTH","app":"google_fit"}]}
Sanatate (Strava): {"actions":[{"type":"READ_HEALTH","app":"strava"}]}
Deschide Revolut: {"actions":[{"type":"READ_BALANCE","app":"revolut"}]}
Deschide BTpay: {"actions":[{"type":"READ_BALANCE","app":"btpay"}]}
Deschide PayPal: {"actions":[{"type":"READ_BALANCE","app":"paypal"}]}
Cauta Amazon: {"actions":[{"type":"SHOP_SEARCH","app":"amazon","query":"casti bluetooth"}]}
Cauta eBay: {"actions":[{"type":"SHOP_SEARCH","app":"ebay","query":"iPhone 14"}]}
Comenzi Amazon: {"actions":[{"type":"SHOP_TRACK","app":"amazon"}]}
Crypto Binance: {"actions":[{"type":"READ_CRYPTO","app":"binance"}]}
Crypto Coinbase: {"actions":[{"type":"READ_CRYPTO","app":"coinbase"}]}
Stiri: {"actions":[{"type":"FETCH_NEWS"}]}
Stiri despre topic: {"actions":[{"type":"FETCH_NEWS","topic":"tehnologie"}]}
Telegram mesaj: {"actions":[{"type":"SEND_TELEGRAM","contact":"Ana","message":"Salut!"}]}
Slack mesaj: {"actions":[{"type":"SEND_SLACK","channel":"#general","message":"Buna ziua echipa"}]}
Notita in Keep: {"actions":[{"type":"WRITE_NOTE_APP","app":"keep","title":"Idee","content":"Cumpar lapte"}]}
Notita in OneNote: {"actions":[{"type":"WRITE_NOTE_APP","app":"onenote","title":"Meeting","content":"Note importante"}]}
Notita in Obsidian: {"actions":[{"type":"WRITE_NOTE_APP","app":"obsidian","title":"Idee","content":"Continut"}]}
Memorie utilizator: {"actions":[{"type":"REMEMBER_FACT","fact":"Nu imi plac castravetii"}]}
Economisire baterie on: {"actions":[{"type":"SET_BATTERY_SAVER","enabled":"true"}]}
Economisire baterie off: {"actions":[{"type":"SET_BATTERY_SAVER","enabled":"false"}]}
Viteza voce mai repede: {"actions":[{"type":"SET_TTS_SPEED","direction":"faster"}]}
Viteza voce mai incet: {"actions":[{"type":"SET_TTS_SPEED","direction":"slower"}]}
YouTube cautare: {"actions":[{"type":"YOUTUBE_SEARCH","query":"tutoriale programare"}]}
YouTube Watch Later (cu cautare): {"actions":[{"type":"YOUTUBE_WATCH_LATER","query":"film documentar natura"}]}
YouTube Watch Later (deschide lista): {"actions":[{"type":"YOUTUBE_WATCH_LATER"}]}
YouTube Library: {"actions":[{"type":"YOUTUBE_LIBRARY"}]}
Uber (locatie curenta -> destinatie): {"actions":[{"type":"RIDESHARE","app":"uber","destination":"Aeroportul Otopeni"}]}
Uber (cu punct de start): {"actions":[{"type":"RIDESHARE","app":"uber","pickup":"Piata Unirii","destination":"Gara de Nord","ride_type":"UberX"}]}
Lyft: {"actions":[{"type":"RIDESHARE","app":"lyft","destination":"Downtown","ride_type":"xl"}]}
Uber Eats burger fara castraveti: {"actions":[{"type":"FOOD_DELIVERY","app":"ubereats","food":"burger","customization":"fara castraveti"}]}
DoorDash pizza: {"actions":[{"type":"FOOD_DELIVERY","app":"doordash","food":"pizza margherita","restaurant":"Pizza Hut"}]}
BT dispozitive: {"actions":[{"type":"BT_LIST_DEVICES"}]}
BT conectare: {"actions":[{"type":"BT_CONNECT","device":"Casti Sony"}]}
BT deconectare: {"actions":[{"type":"BT_DISCONNECT","device":"Casti JBL"}]}
BT asociere: {"actions":[{"type":"BT_PAIR","device":"Casti noi"}]}
Cauta imagini galerie: {"actions":[{"type":"GALLERY_SEARCH","query":"plaja","from_date":"2024-06-01","to_date":"2024-09-01","limit":"10"}]}
Trimite imagine atasata pe WhatsApp: {"actions":[{"type":"SEND_IMAGE","app":"whatsapp","contact":"Ana","use_pending":"true"}]}
Trimite imagine din galerie (dupa ID din cautare): {"actions":[{"type":"SEND_IMAGE","app":"instagram","image_id":"123456"}]}
Trimite imagine pe Telegram: {"actions":[{"type":"SEND_IMAGE","app":"telegram","contact":"Ion","image_id":"789012"}]}

REGULI IMPORTANTE:
- duration_seconds trebuie sa fie string intreg (ex: "300")
- exact="false" daca mesajul nu e citat mot-a-mot
- start_datetime format: "yyyy-MM-dd'T'HH:mm" (ex: "2024-01-15T14:00")
- Daca utilizatorul nu specifica aplicatia de mesagerie (WhatsApp/Instagram/Snapchat/Facebook/Discord/SMS), INTREABA care aplicatie doreste — nu ghici
- "username" = handle/cont in aplicatie (optional, foloseste "contact" ca fallback)
- Pentru VPN, "app" poate fi "surfshark" sau "nordvpn"
- READ_BALANCE deschide DOAR aplicatia bancara; nu poate citi soldul programatic. Spune utilizatorului sa verifice pe ecran.
- SHOP_SEARCH si SHOP_TRACK deschid aplicatia/site-ul; NU plaseaza comenzi si NU adauga in cos automat.
- READ_CRYPTO deschide DOAR aplicatia crypto; nu poate citi soldul programatic si nu trimite crypto.
- MEDIA_CONTROL command poate fi: play, pause, play_pause, next, previous, stop, open, search
- Daca utilizatorul cere ceva ce nu poti face (transfer bancar, comanda online, trimitere crypto), refuza explicit si explica de ce.
- REMEMBER_FACT: foloseste cand utilizatorul spune "retine ca", "tine minte ca", "nu uit ca" sau variante.
- FOOD_DELIVERY nu finalizeaza plata — deschide aplicatia cu cautarea si lasa utilizatorul sa confirme.
- RIDESHARE nu confirma comanda — deschide aplicatia cu destinatia setata si lasa utilizatorul sa apese Confirma.
- BT_CONNECT/BT_DISCONNECT deschide Setarile Bluetooth (aplicatia nu poate conecta programatic dispozitive non-BLE).
- YOUTUBE_SEARCH si YOUTUBE_WATCH_LATER nu redau video automat — cauta/afiseaza doar.
- SET_TTS_SPEED: "faster"/"slower" ajusteaza relativ; "speed" (0.5-2.0) seteaza absolut.
- Daca utilizatorul intreaba despre vreme sau traducere, AI-ul poate raspunde direct fara actiuni.
- GALLERY_SEARCH: cauta in galerie. Reguli:
  * "cea mai recenta poza/imagine", "ultima poza", "ce am fotografiat ultima data" → {"type":"GALLERY_SEARCH","limit":"1"} — imediat
  * "a doua/treia/N-a cea mai recenta poza" → {"type":"GALLERY_SEARCH","limit":"1","offset":"1" (pt a 2-a), "2" (pt a 3-a), etc.}
  * "ultimele N poze" → {"type":"GALLERY_SEARCH","limit":"N"}
  * "poze din [perioada]" fara descriere → {"type":"GALLERY_SEARCH","from_date":"...","to_date":"...","limit":"10"}
  * "poza de la [ora/data exacta]" → {"type":"GALLERY_SEARCH","from_date":"[ora/data]","limit":"1"}
  * "poza cu [subiect]" cu perioada specificata → {"type":"GALLERY_SEARCH","query":"subiect","from_date":"...","limit":"10"}
  * "poza cu [subiect]" fara data/perioada exacta → NU CAUTA DIRECT. Intreaba utilizatorul detalii specifice (ex: "In ce zi/luna/an ai facut poza?", "Era ziua sau noaptea?", "Unde erai?"). Scopul tau este sa restrangi cautarea folosind `from_date` si `to_date` cat mai precis pentru a nu scana toata galeria la intamplare. Abia dupa ce ai o perioada de timp bine definita, executa actiunea de cautare.
  * Vision model primeste data/ora pozelor si le poate filtra dupa detalii temporale fine (ex: "poza cu apus de aseara de la 8").
  Raspunde cu lista de imagini gasite, apoi intreaba ce vrea sa faca cu ele.
- SEND_IMAGE: trimite imaginea atasata (use_pending=true) sau o imagine din galerie (image_id=ID din cautare anterioara). Specifica intotdeauna app si contact (unde e necesar).
- Daca utilizatorul a atasat o imagine si cere sa o trimita, foloseste SEND_IMAGE cu use_pending=true.
- Nu trimite imagini fara confirmare explicita din partea utilizatorului.
""".trimIndent()
    }

    private val classifyPrompt = """
Clasifica cererea de mai jos ca SIMPLU sau COMPLEX.
SIMPLU: raspunsuri rapide, identificare obiecte, calcule, traduceri, timere, notite, setari sistem (luminozitate, volum, DND, economisire baterie, viteza voce), navigare GPS, VPN, calendar, control media (play/pause/skip), YouTube cautare, smart home, sanatate, sold bancar, crypto, stiri, shopping cautare/comenzi, bluetooth lista/conectare, memorie utilizator, cautare galerie foto.
COMPLEX: trimitere mesaje (WhatsApp/Telegram/Slack/Instagram/Snapchat/Facebook/Discord/SMS/email), trimitere imagini, apeluri, cautare contacte, livrare mancare, ride-sharing (Uber/Lyft), orchestrare multi-pas.
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
            executor?.executeAll(cleanParsed.actions, imageBase64) ?: emptyList()
        } else emptyList()

        val galleryIds = actionResults.flatMap { it.galleryImageIds ?: emptyList() }
            .takeIf { it.isNotEmpty() } ?: lastGalleryIds?.takeIf { it.isNotEmpty() }
        lastGalleryIds = null  // reset for next call
        return RouteResult(firstResponse, cleanParsed, useExpert, actionResults, galleryIds)
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

    private suspend fun buildDataContext(req: com.lumi.app.actions.DataRequest): String {
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
                if (found != null) sb.appendLine("$name → ${found.name} (${found.phoneNumbers.joinToString()})${if (found.emails.isNotEmpty()) " email: ${found.emails.first()}" else ""}")
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
            val samsungNotes = notes.readSamsungNotes(5)
            if (samsungNotes.isNotEmpty()) {
                sb.appendLine("=== Samsung Notes (recente) ===")
                samsungNotes.forEach { sb.appendLine(it) }
            }
        }

        if (req.calendar) {
            sb.appendLine("=== Evenimente viitoare ===")
            sb.appendLine(calendar.formatSummary(10))
        }

        req.gallery?.let { gr ->
            sb.appendLine("=== Imagini Galerie ===")
            val helper = gallerySearchHelper
            if (helper == null) {
                sb.appendLine("Galerie indisponibila.")
            } else {
                val all      = helper.queryRecent(GallerySearchHelper.MAX_SCAN)
                val fromMs   = GallerySearchHelper.parseDateString(gr.from_date)
                val toMs     = GallerySearchHelper.parseDateString(gr.to_date)
                val filtered = helper.filterByDateRange(all, fromMs, toMs)
                val pool     = if (gr.offset > 0) filtered.drop(gr.offset) else filtered
                val results = if (!gr.query.isNullOrBlank()) {
                    helper.findByVision(pool, gr.query, client, maxResults = gr.limit)
                } else {
                    pool.take(gr.limit)
                }
                lastGalleryIds = results.map { it.id }
                if (results.isEmpty()) sb.appendLine("Nu s-au gasit imagini.")
                else sb.appendLine(helper.formatSummary(results))
            }
        }

        return sb.toString().trim()
    }
}
