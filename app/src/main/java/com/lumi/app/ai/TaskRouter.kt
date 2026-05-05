package com.lumi.app.ai

import android.net.Uri
import android.os.Build
import com.lumi.app.actions.ActionExecutor
import com.lumi.app.actions.ActionParser
import com.lumi.app.actions.ActionValidator
import com.lumi.app.bluetooth.BluetoothDeviceManager
import com.lumi.app.calendar.CalendarHelper
import com.lumi.app.contacts.ContactsHelper
import com.lumi.app.gallery.GallerySearchHelper
import com.lumi.app.location.LocationHelper
import com.lumi.app.location.PlaceSearchHelper
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
    private val gallerySearchHelper: GallerySearchHelper? = null,
    private val documentHelper: com.lumi.app.system.DocumentHelper,
    private val locationHelper: LocationHelper? = null,
    private val placeSearchHelper: PlaceSearchHelper? = null
) {
    data class RouteResult(
        val response: GeminiResponse,
        val parsed: com.lumi.app.actions.ParsedAIResponse,
        val usedExpert: Boolean,
        val actionResults: List<ActionExecutor.Result> = emptyList(),
        val galleryImageIds: List<Long>? = null,
        val validationIssues: List<ActionValidator.Issue> = emptyList()
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
        val helper = locationHelper
        val gps = helper?.getLastKnown()
        val gpsSection = if (helper != null && gps != null)
            "\nLocatie GPS: ${helper.format(gps)} (lat=${gps.lat}, lon=${gps.lon})"
            else ""
        return """
Data si ora: $now
Dispozitiv: $model
Luminozitate: $brightness% | Volum media: $volumeMedia% | Volum sonerie: $volumeRing% | Nu deranjati: $dnd | Economisire baterie: $batterySaver
Bluetooth: $btDevices$gpsSection$memSection
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
{"notifications":true,"contacts":["numeContact"],"whatsapp":{"contact":"numeContact","limit":25},"timers":true,"notes":true,"notes_query":"cuvant cheie","calendar":true,"gallery":{"query":"plaja","from_date":"2024-06-01","to_date":"2024-09-01","limit":10},"file_query":"nume document/fisier sau recent","place_search":{"query":"Primarie","limit":3}}

Include NUMAI campurile necesare. Nu adauga nimic dupa ___LUMI_REQUEST___.

place_search: cauta puncte de reper sau locuri de interes LANGA LOCATIA GPS CURENTA. Foloseste cand utilizatorul spune "langa Primarie", "in centru", "langa banca", "langa piata", "undeva pe strada principala" — indicii in loc de adresa exacta. Dupa ce primesti rezultatele, extrage adresa si executa NAVIGATE_MAPS/WAZE cu ea. Poti combina: "magazin langa Primarie" → place_search="Primarie" → primesti adresa → NAVIGATE cu "magazin langa [adresa primita]".

Galerie foto: "cea mai recenta poza", "ultima poza", "ce am fotografiat ultima data" → cauta direct fara a intreba. Pentru cautari cu descriere fara data → intreaba perioada. Cauta doar in top 1000 imagini recente. Poti cauta si dupa offset (ex: "a doua cea mai recenta" → limit:1, offset:1).

LOCATII SALVATE: Utilizatorul poate salva locatii cu porecle (acasa, birou, sala, parinti, etc.). Aceste porecle apar in "Preferinte utilizator memorate" de mai sus. Cand utilizatorul spune "acasa", "birou", "serviciu" etc., cauta INTAI in memorie adresa corespunzatoare si foloseste-o direct fara sa mai intrebi. Daca nu e salvata, intreaba adresa si RETINE-O automat (REMEMBER_FACT + actiunea ceruta in acelasi mesaj).
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
Pauza timer dupa nume: {"actions":[{"type":"PAUSE_TIMER","name":"Paste"}]}
Opreste al doilea timer (index 1-based): {"actions":[{"type":"CANCEL_TIMER","index":"2"}]}
Reseteaza ultimul timer activ: {"actions":[{"type":"RESET_TIMER"}]}
WhatsApp: {"actions":[{"type":"SEND_WHATSAPP","contact":"Mama","message":"Vin acasa","exact":"true"}]}
SMS: {"actions":[{"type":"SEND_SMS","contact":"Ana","message":"Salut"}]}
Instagram: {"actions":[{"type":"SEND_INSTAGRAM","contact":"Ana","username":"ana.ig","message":"Buna"}]}
Snapchat: {"actions":[{"type":"SEND_SNAPCHAT","contact":"Ion","username":"ion_snap","message":"Salut"}]}
Facebook Messenger: {"actions":[{"type":"SEND_FACEBOOK","contact":"Maria","message":"Ce faci?"}]}
Discord: {"actions":[{"type":"SEND_DISCORD","contact":"Alex","username":"alex#1234","message":"Hey"}]}
Email: {"actions":[{"type":"SEND_EMAIL","to":"ana@gmail.com","subject":"Re: intalnire","body":"Ne vedem maine la 10."}]}
Email cu fisier de pe telefon: {"actions":[{"type":"SEND_EMAIL","to":"sefu@firma.ro","subject":"Raport","body":"Atasat raportul.","attachment_query":"raport"}]}
Email cu fisierul atasat (clip): {"actions":[{"type":"SEND_EMAIL","to":"ana@firma.ro","subject":"Doc","body":"Vezi atasament.","attach_pending":"true"}]}
Citeste email: {"actions":[{"type":"READ_EMAIL"}]}
Apel: {"actions":[{"type":"CALL","contact":"Tata"}]}
Notita noua: {"actions":[{"type":"WRITE_NOTE","title":"Cumparaturi","content":"Lapte, oua, paine"}]}
Actualizeaza notita (dupa ID din cautare/listare): {"actions":[{"type":"WRITE_NOTE","id":"ID_NOTITA","content":"Text nou"}]}
Sterge notita (dupa ID): {"actions":[{"type":"DELETE_NOTE","id":"ID_NOTITA"}]}
Sterge notita (dupa titlu): {"actions":[{"type":"DELETE_NOTE","title":"Cumparaturi"}]}
Google Maps: {"actions":[{"type":"NAVIGATE_MAPS","destination":"Piata Universitatii, Bucuresti"}]}
Waze: {"actions":[{"type":"NAVIGATE_WAZE","destination":"Aeroportul Henri Coanda"}]}
Navigheaza + salveaza locatia noua: {"actions":[{"type":"NAVIGATE_MAPS","destination":"Str. Florilor 12, Cluj"},{"type":"REMEMBER_FACT","fact":"acasa = Str. Florilor 12, Cluj"}]}
Navigheaza spre birou (adresa deja in memorie): {"actions":[{"type":"NAVIGATE_MAPS","destination":"[adresa din memorie]"}]}
Surfshark conectare: {"actions":[{"type":"CONNECT_VPN","app":"surfshark","country":"Romania"}]}
NordVPN deconectare: {"actions":[{"type":"DISCONNECT_VPN","app":"nordvpn"}]}
Event calendar: {"actions":[{"type":"CREATE_EVENT","title":"Intalnire","description":"Discutie proiect","location":"Birou","start_datetime":"2024-01-15T14:00","end_datetime":"2024-01-15T15:00"}]}
Calendar: {"actions":[{"type":"READ_CALENDAR"}]}
Sterge eveniment (dupa ID din READ_CALENDAR): {"actions":[{"type":"DELETE_EVENT","id":"12345"}]}
Sterge eveniment (dupa titlu, daca nu ai ID): {"actions":[{"type":"DELETE_EVENT","title":"Intalnire"}]}
Actualizeaza eveniment: {"actions":[{"type":"UPDATE_EVENT","id":"12345","new_title":"Intalnire amanata","start_datetime":"2024-01-15T16:00","end_datetime":"2024-01-15T17:00"}]}
Muta eveniment dupa titlu: {"actions":[{"type":"UPDATE_EVENT","lookup_title":"Intalnire","start_datetime":"2024-01-16T14:00","end_datetime":"2024-01-16T15:00"}]}
Luminozitate: {"actions":[{"type":"SET_BRIGHTNESS","level":"60"}]}
Volum absolut: {"actions":[{"type":"SET_VOLUME","stream":"media","level":"50"}]}
Volum mai tare: {"actions":[{"type":"SET_VOLUME","stream":"media","direction":"up"}]}
Volum mai incet: {"actions":[{"type":"SET_VOLUME","stream":"media","direction":"down"}]}
Mute sonerie + DND: {"actions":[{"type":"SET_VOLUME","stream":"ring","direction":"mute"},{"type":"SET_DND","enabled":"true"}]}
Volume maxim: {"actions":[{"type":"SET_VOLUME","stream":"media","direction":"max"}]}
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
Trimite imagine din galerie (dupa ID din cautare anterioara): {"actions":[{"type":"SEND_IMAGE","app":"instagram","image_id":"123456"}]}
Cauta SI trimite imagine in acelasi mesaj: {"actions":[{"type":"GALLERY_SEARCH","query":"plaja","limit":"5"},{"type":"SEND_IMAGE","app":"whatsapp","contact":"Ana","use_gallery_result":"0"}]}
Trimite a doua imagine gasita: {"actions":[{"type":"SEND_IMAGE","app":"whatsapp","contact":"Mama","use_gallery_result":"1"}]}
Trimite imagine Telegram: {"actions":[{"type":"SEND_IMAGE","app":"telegram","contact":"Ion","image_id":"789012"}]}
Trimite fisier de pe telefon: {"actions":[{"type":"FORWARD_FILE","app":"whatsapp","contact":"Seful","query":"contract"}]}
Trimite fisier prin email (de pe telefon): {"actions":[{"type":"FORWARD_FILE","app":"email","contact":"sefu@firma.ro","query":"contract","subject":"Contract","body":"Atasat contractul semnat."}]}
Creeaza/modifica fisier si trimite: {"actions":[{"type":"CREATE_FORWARD_FILE","app":"email","contact":"sefu@firma.ro","filename":"lista.txt","new_content":"Lapte\nOua\nPaine","subject":"Lista","body":"Vezi atasamentul."}]}
Trimite fisier atasat via buton: {"actions":[{"type":"SEND_FILE","app":"whatsapp","contact":"Ana"}]}
Trimite fisier atasat via email: {"actions":[{"type":"SEND_FILE","app":"email","contact":"ana@gmail.com","subject":"Doc","body":"Vezi atasament."}]}
Editeaza fisier atasat via buton: {"actions":[{"type":"EDIT_FILE","new_content":"continut complet nou"}]}
Raspunde la ultima notificare (reply rapid, NU deschide app): {"actions":[{"type":"REPLY_NOTIFICATION","message":"Vin in 10 minute"}]}
Raspunde la mesaj WhatsApp de la un contact anume: {"actions":[{"type":"REPLY_NOTIFICATION","app":"whatsapp","contact":"Ana","message":"Ok, multumesc"}]}
Raspunde la mesaj dintr-o aplicatie: {"actions":[{"type":"REPLY_NOTIFICATION","app":"telegram","message":"Salut"}]}

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
  * "cea mai recenta poza/imagine", "ultima poza", "ultimul screenshot", "ce am fotografiat ultima data" → {"type":"GALLERY_SEARCH","limit":"1"} — imediat, fara intrebari
  * "a doua/treia/N-a cea mai recenta poza" → {"type":"GALLERY_SEARCH","limit":"1","offset":"1"/"2"/etc.}
  * "ultimele N poze" → {"type":"GALLERY_SEARCH","limit":"N"}
  * "poze din [perioada]" fara descriere → {"type":"GALLERY_SEARCH","from_date":"...","to_date":"...","limit":"10"}
  * "poza de la [ora/data exacta]" → {"type":"GALLERY_SEARCH","from_date":"[data]","limit":"1"}
  * "poza cu [subiect]" cu perioada specificata → {"type":"GALLERY_SEARCH","query":"subiect","from_date":"...","limit":"10"}
  * "poza cu [subiect]" fara data → intreaba DOAR perioada (nu mai intreba subiectul — il stii deja)
  * Cand returnezi imagini gasite, listeaza-le INTOTDEAUNA in formatul "[N] ID:XXXXX | data | nume" ca sa poata fi referentiate ulterior.
  * Dupa ce ai afisat imaginile, intreaba utilizatorul ce doreste sa faca cu ele.
- SEND_IMAGE: trimite o imagine. Reguli de selectie:
  * Utilizator a atasat imagine via buton → use_pending="true"
  * Utilizator spune "trimite-o/trimite-l/trimite poza/imaginea" DUPA o cautare in acelasi mesaj → use_gallery_result="0"
  * Utilizator spune "trimite prima/a doua" → use_gallery_result="0"/"1"/etc.
  * Utilizator spune "trimite poza" si exista imagini gasite in [Rezultate:] din conversatia anterioara → image_id=[ID-ul din acel rezultat]
  * Cauta SI trimite in ACELASI mesaj → GALLERY_SEARCH urmat de SEND_IMAGE cu use_gallery_result="0"
- Imagini/fisiere: verifica intai daca e atasat ceva (=== Fisier atasat: === sau imagine) inainte sa cauti.
- Fisiere — patru actiuni + email cu atasament, nu le confunda:
  * SEND_FILE: trimite fisierul ATASAT DE UTILIZATOR via butonul de atasare (iconita clip)
  * FORWARD_FILE: cauta un fisier dupa NUME pe telefonul utilizatorului si il trimite (caut in Downloads, Documents, WhatsApp Documents/Images/Video, Telegram, Signal, etc.)
  * EDIT_FILE: scrie continut nou in fisierul ATASAT
  * CREATE_FORWARD_FILE: creeaza versiune modificata a unui fisier gasit pe telefon si o trimite
  * SEND_EMAIL cu attachment_query: trimite email cu un fisier de pe telefon (echivalent FORWARD_FILE app=email, dar mai natural pentru email pur)
  * SEND_EMAIL cu attach_pending=true: trimite email cu fisierul atasat (clip)
  * file_query in LUMI_REQUEST: citeste continutul unui fisier de pe telefon (PDF, txt, docx) ca context — foloseste cand trebuie sa CITESTI/REZUMI un fisier, nu sa-l trimiti
- Pentru email cu atasament: prefera `SEND_EMAIL` cu `attachment_query` sau `attach_pending` in loc de `FORWARD_FILE app=email` cand utilizatorul cere clar un email (cu subiect/corp).
- FLUX TIPIC ("ia fisierul X de pe WhatsApp, modifica-l si trimite-l pe email"): un singur mesaj, doua actiuni —
  1. {"type":"CREATE_FORWARD_FILE","app":"email","contact":"...","filename":"...","new_content":"...","subject":"...","body":"..."}
  Sau, daca nu trebuie modificat: {"type":"SEND_EMAIL","to":"...","subject":"...","body":"...","attachment_query":"nume fisier"}
  Aplicatia cauta automat fisierul in Downloads + WhatsApp + Telegram + alte directoare de mesagerie.
- Daca o aplicatie tinta lipseste, sistemul deschide automat un selector "Trimite via" — utilizatorul alege ce app vrea (Gmail/Outlook/etc.) si fisierul se ataseaza la fel.
- Nu trimite imagini sau fisiere fara confirmare explicita din partea utilizatorului.
- Timere — "al doilea timer", "primul", "ultimul": foloseste "index" (1-based) dupa ce ai vazut lista din "timers":true. Daca utilizatorul spune doar "opreste timerul" si exista unul singur activ, trimite actiunea fara name/index — se va rezolva automat.
- Notite — dupa notes:true sau notes_query, fiecare notita incepe cu "ID:XXXXX". Pentru WRITE_NOTE (editare) sau DELETE_NOTE foloseste EXACT acel ID. Poti folosi si "title" ca fallback daca nu exista ID in context.
- Calendar — dupa READ_CALENDAR sau calendar:true, fiecare eveniment incepe cu "ID:XXXXX". Pentru DELETE_EVENT si UPDATE_EVENT foloseste acel ID. Ca fallback: "title" pentru DELETE_EVENT, "lookup_title" pentru UPDATE_EVENT.
- Raspuns la notificari — REPLY_NOTIFICATION trimite direct raspuns FARA a deschide aplicatia (mai rapid decat SEND_WHATSAPP). Reguli:
  * Functioneaza DOAR la notificarile marcate [replyable] in lista de notificari
  * Daca utilizatorul spune "raspunde-i lui Ana", "reply cu X", "spune-i ca" dupa ce a primit o notificare → foloseste REPLY_NOTIFICATION
  * Daca vrei sa raspunzi dar notificarea nu e [replyable], foloseste SEND_WHATSAPP/SEND_TELEGRAM ca fallback
  * Parametrul "contact" filtreaza dupa numele din titlul notificarii (optional)
- Referinte din turul anterior: dupa ce ai executat actiuni, rezultatele apar in istoria conversatiei sub "[Rezultate:]". Foloseste ID-urile (notite, imagini, evenimente) din acele rezultate cand utilizatorul spune "trimite-o", "sterge-l", "editeaza asta".
- Auto-corectare din feedback: in [Rezultate:] poti vedea linii care incep cu "FAILED ACTION_TIP:" (actiunea s-a executat dar nu a reusit) sau "INVALID ACTION_TIP:" (actiunea a fost respinsa de validator). Cand vezi acestea:
  * INVALID + lipsa parametru → cere utilizatorului informatia lipsa SAU re-emite actiunea cu parametrii corecti
  * INVALID + tip gresit → corecteaza formatul (ex: "duration_seconds" trebuie sa fie integer string ca "300", nu "5 minute")
  * FAILED "Contactul X negasit" → cere DataRequest contacts:["X"] in turul urmator si reincearca cu numele exact gasit
  * FAILED "Fisierul X nu a fost gasit" → intreaba utilizatorul unde e fisierul SAU cere file_query mai larg (ex: doar prenumele documentului)
  * FAILED "Anulat" → utilizatorul a refuzat — NU reincerca; intreaba ce sa facem in schimb
  * FAILED app nu instalata → sugereaza alternativa (ex: WhatsApp lipsa → ofera SMS sau email)
  * NU repeta orbeste o actiune care a esuat de acelasi mod; schimba abordarea sau cere clarificare.

INTELEGE FORMULARILE NATURALE — mapeaza imediat fara sa ceri clarificari:
Navigare:
  * "du-ma acasa/home", "vreau sa merg acasa", "navigheza acasa" → cauta "acasa" in memorie → daca gasesti: NAVIGATE_MAPS/WAZE cu acea adresa. Daca NU: intreaba adresa, apoi executa NAVIGATE_MAPS + REMEMBER_FACT("acasa = [adresa data]") impreuna.
  * "du-ma la birou/serviciu/munca/work/office" → cauta "birou/serviciu/munca" in memorie → similar
  * "du-ma la [locatie numita]" (sala, parinti, mall, etc.) → cauta in memorie → daca nu e salvata, intreaba si salveaza
  * "cum ajung la X", "calea spre X", "deschide navigatia spre X" → NAVIGATE_MAPS cu X
  * "du-ma acolo" dupa ce o locatie a fost mentionata → navigheaza la ultima locatie mentionata in conversatie
  * Cand utilizatorul da o adresa ca raspuns la "unde e X?", executa imediat NAVIGATE + REMEMBER_FACT in acelasi mesaj
  * "e langa Primarie/piata/parc/centru/banca/gara" → place_search={"query":"[punct de reper]","limit":3} → primesti adresa exacta → NAVIGATE_MAPS cu destinatia combinata
  * "magazinul de langa Primarie" → place_search="Primarie" ca reper, apoi navigheaza spre "magazin near [adresa obtinuta]"
  * Indiciile pot fi in romana: "Piata Victoriei", "Gara", "Primărie", "Parcul Central", "Spitalul Judetean"
Volum/Audio:
  * "mai tare", "creste sunetul/volumul", "volume up" → SET_VOLUME direction=up
  * "mai incet", "coboara volumul", "volume down" → SET_VOLUME direction=down
  * "mute", "pe mut", "liniste", "silentios" → SET_VOLUME stream=ring direction=mute + SET_DND enabled=true
  * "scoate din mute", "reactivate soneria" → SET_VOLUME stream=ring level=80 + SET_DND enabled=false
  * "volum maxim/full" → SET_VOLUME direction=max
Media:
  * "pune muzica", "da drumul la muzica", "play" → MEDIA_CONTROL command=play
  * "opreste muzica", "stop" → MEDIA_CONTROL command=stop
  * "pauza" → MEDIA_CONTROL command=pause
  * "urmatoarea/skip/sari" → MEDIA_CONTROL command=next
  * "anterioara/inapoi" → MEDIA_CONTROL command=previous
  * "pune [artist/melodie]" → MEDIA_CONTROL command=search query=[artist/melodie]
  * "deschide Spotify/YouTube Music/Netflix cu X" → MEDIA_CONTROL command=open/search app=... query=X
Setari rapide:
  * "nu ma deranja", "nu deranja pe nimeni", "focus mode" → SET_DND enabled=true
  * "trezeste-ma la X" / "pune alarma la X" → SET_ALARM
  * "pune un timer de X minute/ore" → SET_TIMER
  * "porneste cronometrul" → SET_STOPWATCH
  * "dimineaza luminozitate mica", "ecran mai luminos" → SET_BRIGHTNESS
Memorie proactiva:
  * Cand utilizatorul mentioneaza o preferinta ("nu-mi place X", "mereu fac Y", "numele meu e X") → adauga automat REMEMBER_FACT fara sa fi fost cerut explicit
  * Cand raspunde la o intrebare cu o adresa/locatie → salveaza automat cu REMEMBER_FACT
  * Dupa ce utilizatorul corecteaza AI-ul ("nu, vreau X nu Y") → daca corectia e o preferinta, salveaz-o

ECRAN MASINA (Android Auto / Apple CarPlay):
Aplicatiile de navigare si media ruleaza pe telefon si sunt afisate AUTOMAT pe ecranul masinii cand e conectat prin Android Auto sau Apple CarPlay. Nu trebuie actiune separata. Acest lucru se aplica pentru:
  * NAVIGATE_MAPS / NAVIGATE_WAZE → apare pe ecranul masinii
  * MEDIA_CONTROL (Spotify, YouTube Music) → controlabil de pe ecranul masinii
  * Apeluri telefonice → audio prin sistemul masinii
  * YouTube, YouTube Music, Spotify → vizibil pe ecranul masinii
Cand utilizatorul spune "pune pe masina", "redirectioneaza la masina", "vreau pe ecranul masinii" → executa actiunea normal (navigare/media) si mentioneaza ca va aparea automat pe masina daca Android Auto e conectat. Nu exista o actiune separata de "trimite la masina" — conexiunea e gestionata automat de Android.
Daca Bluetooth-ul arata un dispozitiv conectat care pare a fi o masina (ex: "BMW", "Toyota", "Dacia" etc.), mentioneaza explicit ca navigarea/media va aparea pe ecranul masinii.

RETINERE PROACTIVA — integrare totala:
Daca utilizatorul da o informatie utila ca raspuns la o intrebare a ta (adresa, preferinta, nume, numar), SALVEAZ-O automat in memorie adaugand REMEMBER_FACT in blocul de actiuni, chiar daca utilizatorul nu a cerut explicit. Exemplu: utilizatorul raspunde "acasa e la Str. X 5, Cluj" → executa NAVIGATE_MAPS("Str. X 5, Cluj") + REMEMBER_FACT("acasa = Str. X 5, Cluj") in acelasi mesaj.
""".trimIndent()
    }

    private val classifyPrompt = """
Clasifica cererea de mai jos ca SIMPLU sau COMPLEX.
SIMPLU: raspunsuri rapide, identificare obiecte, calcule, traduceri, timere, notite, setari sistem (luminozitate, volum, DND, economisire baterie, viteza voce), navigare GPS (inclusiv "du-ma acasa/la birou"), VPN, calendar (citire/creare/stergere/editare event), control media (play/pause/skip/volum), YouTube cautare, smart home, sanatate, sold bancar, crypto, stiri, shopping cautare/comenzi, bluetooth lista/conectare, memorie utilizator, cautare galerie foto, citire fisier atasat, raspuns la notificari (REPLY_NOTIFICATION), retinere fapte (REMEMBER_FACT).
COMPLEX: trimitere mesaje noi (WhatsApp/Telegram/Slack/Instagram/Snapchat/Facebook/Discord/SMS/email), trimitere imagini, trimitere fisiere, editare fisiere, apeluri, cautare contacte, livrare mancare, ride-sharing (Uber/Lyft), orchestrare multi-pas cu mai multi destinatari.
Raspunde cu UN SINGUR CUVANT: SIMPLU sau COMPLEX
""".trimIndent()

    // ─── Main entry ──────────────────────────────────────────────────────────

    suspend fun route(
        userPrompt: String,
        imageBase64: String?,
        memory: ConversationMemory,
        fastWordLimit: Int = 45,
        expertWordLimit: Int = 95,
        forceExpert: Boolean = false,
        fileContext: String? = null,
        fileUri: Uri? = null,
        /** When non-null, visible-text deltas of the FINAL response are emitted here. */
        onVisibleChunk: ((String) -> Unit)? = null,
        /** Called between the data-request call and the final call, so the UI can clear stream-1 text. */
        onStreamReset: (() -> Unit)? = null
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

        // Prepend file context to user prompt so the AI can read it
        val effectivePrompt = if (fileContext != null) "$fileContext\n\n$userPrompt" else userPrompt

        // First call always streams (when enabled). If a data request comes back, we'll
        // reset the UI buffer and stream a second call with the real reply.
        var firstResponse = client.generate(
            prompt = effectivePrompt, imageBase64 = imageBase64,
            model = model, history = history, systemInstruction = sysPrompt,
            onVisibleChunk = onVisibleChunk
        )
        var parsed = ActionParser.parse(firstResponse.text)

        if (parsed.dataRequest != null) {
            onStreamReset?.invoke()
            val dataContext = buildDataContext(parsed.dataRequest!!)
            val enrichedPrompt = "$effectivePrompt\n\n[Datele cerute:]\n$dataContext"
            firstResponse = client.generate(
                prompt = enrichedPrompt, imageBase64 = imageBase64,
                model = model, history = history, systemInstruction = sysPrompt,
                onVisibleChunk = onVisibleChunk
            )
            parsed = ActionParser.parse(firstResponse.text)
        }

        val cleanParsed = parsed.copy(displayText = stripMarkdown(parsed.displayText))

        // Validate before executing — drop malformed actions, surface the issues to the user.
        val report = ActionValidator.validate(cleanParsed.actions)
        val safeActions = report.valid

        val actionResults = if (settings.actionModeEnabled && safeActions.isNotEmpty()) {
            executor?.executeAll(safeActions, imageBase64, fileUri) ?: emptyList()
        } else emptyList()

        val galleryIds = actionResults.flatMap { it.galleryImageIds ?: emptyList() }
            .takeIf { it.isNotEmpty() } ?: lastGalleryIds?.takeIf { it.isNotEmpty() }
        lastGalleryIds = null  // reset for next call
        return RouteResult(firstResponse, cleanParsed, useExpert, actionResults, galleryIds, report.issues)
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
                if (found.isEmpty()) "Nicio notita cu '$query'."
                else found.joinToString("\n---\n") { "ID:${it.id} | ${it.title}\n${it.content}" }
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

        req.file_query?.let { fq ->
            sb.appendLine("=== Continut Fisier/Document ===")
            val file = documentHelper.findRecentFile(fq)
            if (file == null) {
                sb.appendLine("Nu am gasit niciun fisier cu numele/cererea '$fq' in folderele de fisiere.")
            } else {
                sb.appendLine("Fisier gasit: ${file.name} (Modificat: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))})")
                val txt = documentHelper.extractText(file)
                sb.appendLine("--- Continut ---")
                sb.appendLine(txt.take(15000))
            }
        }

        req.place_search?.let { ps ->
            if (ps.query.isBlank()) {
                sb.appendLine("=== Cautare Locuri ===")
                sb.appendLine("Cere o locatie specifica (ex: 'Primarie', 'banca', 'magazin')")
            } else {
                sb.appendLine("=== Cautare Locuri: \"${ps.query}\" ===")
                val gps = locationHelper?.getLastKnown()
                if (gps == null) {
                    sb.appendLine("GPS indisponibil. Foloseste adresa completa a locatiei sau activeaza permisiunea de locatie.")
                } else {
                    val searcher = placeSearchHelper
                    if (searcher == null) {
                        sb.appendLine("Serviciu cautare locuri indisponibil.")
                    } else {
                        val city = searcher.reverseGeocode(gps.lat, gps.lon)
                        val cityNote = if (city != null) " (oras detectat: $city)" else ""
                        sb.appendLine("Locatie curenta: ${gps.lat}, ${gps.lon}$cityNote")
                        val places = searcher.search(ps.query, gps.lat, gps.lon, ps.limit)
                        if (places.isEmpty()) {
                            sb.appendLine("Nu s-au gasit locuri pentru '${ps.query}' in zona.")
                        } else {
                            places.forEachIndexed { i, p ->
                                sb.appendLine("[${i + 1}] ${p.name}")
                                sb.appendLine("    Adresa completa: ${p.address}")
                                sb.appendLine("    Coordonate: ${p.lat}, ${p.lon}")
                            }
                            sb.appendLine("Foloseste adresa [1] ca referinta in NAVIGATE_MAPS/WAZE.")
                        }
                    }
                }
            }
        }

        return sb.toString().trim()
    }
}
