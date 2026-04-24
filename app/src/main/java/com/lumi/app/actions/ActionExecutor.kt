package com.lumi.app.actions

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import androidx.core.content.FileProvider
import com.lumi.app.bluetooth.BluetoothDeviceManager
import com.lumi.app.calendar.CalendarHelper
import com.lumi.app.consent.ConsentManager
import com.lumi.app.consent.ConsentMode
import com.lumi.app.contacts.ContactsHelper
import com.lumi.app.gallery.GallerySearchHelper
import com.lumi.app.messaging.MessageSender
import com.lumi.app.messaging.SendResult
import com.lumi.app.notes.NoteAppResult
import com.lumi.app.notes.NotesHelper
import com.lumi.app.notes.UserMemory
import com.lumi.app.settings.AppSettings
import com.lumi.app.system.SystemSettingsHelper
import com.lumi.app.timer.TimerManager
import com.lumi.app.tts.LumiTTS
import com.lumi.app.whatsapp.LumiAccessibilityService
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ActionExecutor(
    private val context: Context,
    private val timers: TimerManager,
    private val consent: ConsentManager,
    private val contacts: ContactsHelper,
    private val messenger: MessageSender,
    private val notes: NotesHelper,
    private val sysSettings: SystemSettingsHelper,
    private val getMode: () -> ConsentMode,
    private val userMemory: UserMemory,
    private val btDeviceManager: BluetoothDeviceManager,
    private val lumiDeviceAddress: String,
    private val tts: LumiTTS? = null,
    private val appSettings: AppSettings? = null,
    private val gallerySearchHelper: GallerySearchHelper? = null
) {
    data class Result(val action: LumiAction, val success: Boolean, val message: String, val galleryImageIds: List<Long>? = null)

    private var currentImageBase64: String? = null

    suspend fun executeAll(actions: List<LumiAction>, imageBase64: String? = null): List<Result> {
        currentImageBase64 = imageBase64
        return actions.map { runCatching { execute(it) }.getOrElse { e -> Result(it, false, "Eroare: ${e.message}") } }
    }

    private suspend fun execute(a: LumiAction): Result = when (a.type.uppercase()) {
        "SET_TIMER"       -> setTimer(a)
        "SET_STOPWATCH"   -> setStopwatch(a)
        "SET_ALARM"       -> setAlarm(a)
        "PAUSE_TIMER", "PAUSE_STOPWATCH"   -> pauseTimer(a)
        "RESUME_TIMER", "RESUME_STOPWATCH" -> resumeTimer(a)
        "CANCEL_TIMER", "CANCEL_STOPWATCH", "CANCEL_ALARM" -> cancelTimer(a)
        "RESET_TIMER", "RESET_STOPWATCH"   -> resetTimer(a)
        "SEND_WHATSAPP"        -> sendWhatsApp(a)
        "SEND_SMS"             -> sendSms(a)
        "SEND_INSTAGRAM"       -> sendSocialApp(a, "Instagram", "com.instagram.android")
        "SEND_SNAPCHAT"        -> sendSocialApp(a, "Snapchat", "com.snapchat.android")
        "SEND_FACEBOOK"        -> sendSocialApp(a, "Messenger", "com.facebook.orca")
        "SEND_DISCORD"         -> sendSocialApp(a, "Discord", "com.discord")
        "SEND_TELEGRAM"        -> sendTelegram(a)
        "SEND_SLACK"           -> sendSlack(a)
        "SEND_EMAIL"           -> sendEmail(a)
        "READ_EMAIL"           -> readEmail(a)
        "CALL"                 -> call(a)
        "WRITE_NOTE"           -> writeNote(a)
        "WRITE_NOTE_APP"       -> writeNoteInApp(a)
        "REMEMBER_FACT"        -> rememberFact(a)
        "NAVIGATE_MAPS"        -> navigateMaps(a)
        "NAVIGATE_WAZE"        -> navigateWaze(a)
        "CONNECT_VPN"          -> toggleVpn(a, true)
        "DISCONNECT_VPN"       -> toggleVpn(a, false)
        "CREATE_EVENT"         -> createEvent(a)
        "READ_CALENDAR"        -> readCalendar(a)
        "SET_BRIGHTNESS"       -> setBrightness(a)
        "SET_VOLUME"           -> setVolume(a)
        "SET_DND"              -> setDND(a)
        "SET_BATTERY_SAVER"    -> setBatterySaver(a)
        "SET_TTS_SPEED"        -> setTtsSpeed(a)
        "MEDIA_CONTROL"        -> mediaControl(a)
        "YOUTUBE_SEARCH"       -> youtubeSearch(a)
        "YOUTUBE_WATCH_LATER"  -> youtubeWatchLater(a)
        "YOUTUBE_LIBRARY"      -> youtubeLibrary(a)
        "HOME_CONTROL"         -> homeControl(a)
        "READ_HEALTH"          -> readHealth(a)
        "READ_BALANCE"         -> readBalance(a)
        "SHOP_SEARCH"          -> shopSearch(a)
        "SHOP_TRACK"           -> shopTrack(a)
        "READ_CRYPTO"          -> readCrypto(a)
        "FETCH_NEWS"           -> fetchNews(a)
        "FOOD_DELIVERY"        -> foodDelivery(a)
        "RIDESHARE"            -> rideshare(a)
        "BT_LIST_DEVICES"      -> btListDevices(a)
        "BT_CONNECT"           -> btConnect(a)
        "BT_DISCONNECT"        -> btDisconnect(a)
        "BT_PAIR"              -> btPair(a)
        "GALLERY_SEARCH"       -> gallerySearch(a)
        "SEND_IMAGE"           -> sendImage(a)
        else -> Result(a, false, "Actiune necunoscuta: ${a.type}")
    }

    // ─── Timers ──────────────────────────────────────────────────────────────

    private fun setTimer(a: LumiAction): Result {
        val name = a.params["name"] ?: "Timer"
        val secs = a.params["duration_seconds"]?.toLongOrNull()
            ?: return Result(a, false, "Durata lipsa pentru timer.")
        timers.setTimer(name, secs)
        return Result(a, true, "Timer \"$name\" setat: ${timers.fmtSecs(secs)}.")
    }

    private fun setStopwatch(a: LumiAction): Result {
        val name = a.params["name"] ?: "Cronometru"
        timers.startStopwatch(name)
        return Result(a, true, "Cronometru \"$name\" pornit.")
    }

    private fun setAlarm(a: LumiAction): Result {
        val name = a.params["name"] ?: "Alarma"
        val time = a.params["time_24h"] ?: return Result(a, false, "Ora lipsa.")
        val parts = time.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return Result(a, false, "Ora invalida.")
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
            if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        timers.setAlarm(name, cal.timeInMillis)
        return Result(a, true, "Alarma \"$name\" setata la $time.")
    }

    private fun pauseTimer(a: LumiAction): Result {
        val t = timers.getByName(a.params["name"] ?: "") ?: return Result(a, false, "Timer negasit.")
        timers.pause(t.id)
        return Result(a, true, "Timer \"${t.name}\" in pauza.")
    }

    private fun resumeTimer(a: LumiAction): Result {
        val t = timers.getByName(a.params["name"] ?: "") ?: return Result(a, false, "Timer negasit.")
        timers.resume(t.id)
        return Result(a, true, "Timer \"${t.name}\" reluat.")
    }

    private fun cancelTimer(a: LumiAction): Result {
        val t = timers.getByName(a.params["name"] ?: "") ?: return Result(a, false, "Timer negasit.")
        timers.cancel(t.id)
        return Result(a, true, "Timer \"${t.name}\" anulat.")
    }

    private fun resetTimer(a: LumiAction): Result {
        val t = timers.getByName(a.params["name"] ?: "") ?: return Result(a, false, "Timer negasit.")
        timers.reset(t.id)
        return Result(a, true, "Timer \"${t.name}\" resetat.")
    }

    // ─── Messaging ───────────────────────────────────────────────────────────

    private suspend fun sendWhatsApp(a: LumiAction): Result {
        val cName = a.params["contact"] ?: return Result(a, false, "Contact lipsa.")
        val msg   = a.params["message"] ?: return Result(a, false, "Mesaj lipsa.")
        val exact = a.params["exact"] == "true"
        val contact = contacts.findBestMatch(cName)
            ?: return Result(a, false, "Contactul \"$cName\" negasit.")
        val prompt = if (exact) "Trimit WhatsApp lui ${contact.name}: \"$msg\". Confirmi?"
                     else       "Urmezi sa trimit lui ${contact.name}: \"$msg\". Confirmi?"
        if (!consent.request(prompt, getMode())) return Result(a, false, "Anulat.")
        return when (val r = messenger.sendWhatsApp(contact, msg)) {
            is SendResult.SentSilently -> Result(a, true, "Mesaj trimis lui ${contact.name}.")
            is SendResult.DeepLinkOpened -> {
                if (LumiAccessibilityService.isAvailable()) {
                    delay(3500)
                    val sent = LumiAccessibilityService.sendCurrentMessage(msg)
                    if (sent) Result(a, true, "Mesaj trimis lui ${contact.name}.")
                    else Result(a, true, "WhatsApp deschis. Apasa Trimite manual daca nu s-a trimis.")
                } else {
                    Result(a, true, "WhatsApp deschis cu mesajul pre-completat. Apasa Trimite.")
                }
            }
            is SendResult.Error -> Result(a, false, r.reason)
        }
    }

    private suspend fun sendSms(a: LumiAction): Result {
        val cName = a.params["contact"] ?: return Result(a, false, "Contact lipsa.")
        val msg   = a.params["message"] ?: return Result(a, false, "Mesaj lipsa.")
        val contact = contacts.findBestMatch(cName)
            ?: return Result(a, false, "Contactul \"$cName\" negasit.")
        if (!consent.request("Trimit SMS lui ${contact.name}: \"$msg\". Confirmi?", getMode()))
            return Result(a, false, "Anulat.")
        return when (val r = messenger.sendSms(contact, msg)) {
            is SendResult.SentSilently -> Result(a, true, "SMS trimis lui ${contact.name}.")
            is SendResult.DeepLinkOpened -> Result(a, true, "SMS deschis. Apasa Trimite.")
            is SendResult.Error -> Result(a, false, r.reason)
        }
    }

    private suspend fun sendSocialApp(a: LumiAction, displayName: String, pkg: String): Result {
        val cName    = a.params["contact"] ?: return Result(a, false, "Contact lipsa.")
        val msg      = a.params["message"] ?: return Result(a, false, "Mesaj lipsa.")
        val username = a.params["username"] ?: cName
        if (!consent.request("Trimit mesaj pe $displayName lui $username: \"$msg\". Confirmi?", getMode()))
            return Result(a, false, "Anulat.")
        val openResult = when (pkg) {
            "com.instagram.android" -> messenger.openInstagramDM(username)
            "com.snapchat.android"  -> messenger.openSnapchatChat(username)
            "com.facebook.orca"     -> {
                val contact = contacts.findBestMatch(cName)
                if (contact != null) messenger.openFacebookMessenger(contact)
                else messenger.openAppByPackage("com.facebook.orca", null, "Messenger nu este instalat.")
            }
            "com.discord"           -> messenger.openDiscordDM(username)
            else -> SendResult.Error("Aplicatie necunoscuta: $pkg")
        }
        if (openResult is SendResult.Error) return Result(a, false, openResult.reason)
        return if (LumiAccessibilityService.isAvailable()) {
            delay(4000)
            val sent = LumiAccessibilityService.sendSocialMessage(pkg, username, msg)
            if (sent) Result(a, true, "Mesaj trimis pe $displayName lui $username.")
            else Result(a, true, "$displayName deschis. Daca nu s-a trimis, apasa Send manual.")
        } else {
            Result(a, true, "$displayName deschis. Trimite mesajul manual.")
        }
    }

    private suspend fun sendEmail(a: LumiAction): Result {
        val toParam  = a.params["to"] ?: a.params["contact"] ?: return Result(a, false, "Destinatar lipsa.")
        val subject  = a.params["subject"] ?: ""
        val body     = a.params["body"] ?: a.params["message"] ?: return Result(a, false, "Continut lipsa.")
        // Resolve email address: use raw if it contains @, otherwise look up contact
        val to = if (toParam.contains("@")) toParam else {
            contacts.findBestMatch(toParam)?.emails?.firstOrNull() ?: toParam
        }
        if (!consent.request("Trimit email la $to: \"$subject\". Confirmi?", getMode()))
            return Result(a, false, "Anulat.")
        return when (messenger.composeEmail(to, subject, body)) {
            is SendResult.DeepLinkOpened -> {
                if (LumiAccessibilityService.isAvailable()) {
                    delay(2500)
                    LumiAccessibilityService.sendGmailAfterCompose()
                    Result(a, true, "Email trimis la $to.")
                } else {
                    Result(a, true, "Email deschis. Apasa Trimite.")
                }
            }
            is SendResult.Error -> Result(a, false, "Nu s-a putut deschide emailul.")
            else -> Result(a, true, "Email deschis.")
        }
    }

    private fun readEmail(a: LumiAction): Result {
        return when (messenger.openGmail()) {
            is SendResult.Error -> Result(a, false, "Gmail nu este instalat.")
            else -> Result(a, true, "Gmail deschis.")
        }
    }

    private suspend fun call(a: LumiAction): Result {
        val cName = a.params["contact"] ?: return Result(a, false, "Contact lipsa.")
        val contact = contacts.findBestMatch(cName)
            ?: return Result(a, false, "Contactul \"$cName\" negasit.")
        val phone = contact.phoneNumbers.firstOrNull() ?: return Result(a, false, "Niciun numar.")
        if (!consent.request("Suni pe ${contact.name}. Confirmi?", getMode()))
            return Result(a, false, "Apel anulat.")
        return try {
            context.startActivity(Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phone"); flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            Result(a, true, "Sun pe ${contact.name}...")
        } catch (e: Exception) { Result(a, false, "Eroare apel: ${e.message}") }
    }

    // ─── Notes ───────────────────────────────────────────────────────────────

    private suspend fun writeNote(a: LumiAction): Result {
        val title   = a.params["title"] ?: ""
        val content = a.params["content"] ?: return Result(a, false, "Continut notita lipsa.")
        val id      = a.params["id"]
        return if (id != null) {
            notes.update(id, title.ifBlank { null }, content)
            Result(a, true, "Notita actualizata.")
        } else {
            val note = notes.create(title, content)
            val effectiveTitle = title.ifBlank { note.title }
            val appResult = notes.createInApp(effectiveTitle, content)
            if (appResult == NoteAppResult.UI_OPENED && LumiAccessibilityService.isAvailable()) {
                delay(2500)
                LumiAccessibilityService.saveSamsungNote()
            }
            Result(a, true, "Notita \"${note.title}\" salvata.")
        }
    }

    // ─── Navigation ──────────────────────────────────────────────────────────

    private fun navigateMaps(a: LumiAction): Result {
        val dest = a.params["destination"] ?: return Result(a, false, "Destinatie lipsa.")
        return when (val r = messenger.openGoogleMaps(dest)) {
            is SendResult.Error -> Result(a, false, r.reason)
            else -> Result(a, true, "Navigare deschisa catre $dest.")
        }
    }

    private fun navigateWaze(a: LumiAction): Result {
        val dest = a.params["destination"] ?: return Result(a, false, "Destinatie lipsa.")
        return when (val r = messenger.openWaze(dest)) {
            is SendResult.Error -> Result(a, false, r.reason)
            else -> Result(a, true, "Waze deschis catre $dest.")
        }
    }

    // ─── VPN ─────────────────────────────────────────────────────────────────

    private suspend fun toggleVpn(a: LumiAction, connect: Boolean): Result {
        val app     = a.params["app"]?.lowercase() ?: "surfshark"
        val country = a.params["country"]
        val pkg = when {
            app.contains("nord") -> "com.nordvpn.android"
            else                 -> "com.surfshark.vpnclient.android"
        }
        val label = if (connect) "Conectare" else "Deconectare"
        val desc  = "$label VPN ($app${if (country != null) " - $country" else ""}). Confirmi?"
        if (!consent.request(desc, getMode())) return Result(a, false, "Anulat.")
        return when (val r = messenger.openVpnApp(pkg)) {
            is SendResult.Error -> Result(a, false, r.reason)
            else -> {
                if (LumiAccessibilityService.isAvailable()) {
                    delay(3000)
                    val toggled = LumiAccessibilityService.tapVpnConnect(pkg, connect)
                    if (toggled) Result(a, true, "VPN ${if (connect) "conectat" else "deconectat"}.")
                    else Result(a, true, "Aplicatia VPN deschisa. Apasa ${if (connect) "Connect" else "Disconnect"} manual.")
                } else {
                    Result(a, true, "Aplicatia VPN deschisa. Apasa ${if (connect) "Connect" else "Disconnect"} manual.")
                }
            }
        }
    }

    // ─── Calendar ────────────────────────────────────────────────────────────

    private fun createEvent(a: LumiAction): Result {
        val title  = a.params["title"] ?: return Result(a, false, "Titlu eveniment lipsa.")
        val desc   = a.params["description"]
        val loc    = a.params["location"]
        val startMs = parseDateTime(a.params["start_datetime"])
            ?: return Result(a, false, "Data/ora start invalida. Foloseste format: yyyy-MM-dd'T'HH:mm")
        val endMs = parseDateTime(a.params["end_datetime"]) ?: (startMs + 3_600_000L)
        CalendarHelper(context).create(title, desc, loc, startMs, endMs)
        return Result(a, true, "Eveniment \"$title\" adaugat in calendar.")
    }

    private fun readCalendar(a: LumiAction): Result {
        val summary = CalendarHelper(context).formatSummary(10)
        return Result(a, true, summary)
    }

    private fun parseDateTime(dtStr: String?): Long? {
        if (dtStr.isNullOrBlank()) return null
        val formats = listOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "dd-MM-yyyy HH:mm", "yyyy-MM-dd")
        for (fmt in formats) {
            try { return SimpleDateFormat(fmt, Locale.getDefault()).parse(dtStr)?.time } catch (_: Exception) {}
        }
        return null
    }

    // ─── System settings ─────────────────────────────────────────────────────

    private fun setBrightness(a: LumiAction): Result {
        val level = a.params["level"]?.toIntOrNull() ?: return Result(a, false, "Nivel lipsa.")
        return sysSettings.setBrightness(level)
            .fold(
                onSuccess = { Result(a, true, "Luminozitate setata la $level%.") },
                onFailure = { Result(a, false, it.message ?: "Eroare luminozitate.") }
            )
    }

    private fun setVolume(a: LumiAction): Result {
        val stream = a.params["stream"] ?: "media"
        val level  = a.params["level"]?.toIntOrNull() ?: return Result(a, false, "Nivel lipsa.")
        sysSettings.setVolume(stream, level)
        return Result(a, true, "Volum $stream setat la $level%.")
    }

    private fun setDND(a: LumiAction): Result {
        val enabled = a.params["enabled"]?.lowercase() == "true"
        return sysSettings.setDND(enabled)
            .fold(
                onSuccess = { Result(a, true, if (enabled) "Nu deranjati activat." else "Nu deranjati dezactivat.") },
                onFailure = { Result(a, false, it.message ?: "Eroare DND.") }
            )
    }

    // ─── Media control ───────────────────────────────────────────────────────

    private fun mediaControl(a: LumiAction): Result {
        val command = a.params["command"]?.lowercase() ?: return Result(a, false, "Comanda media lipsa.")
        val appName = a.params["app"]?.lowercase() ?: "spotify"
        val query   = a.params["query"]

        return when (command) {
            "play", "resume" -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                Result(a, true, "Redare pornita.")
            }
            "pause" -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                Result(a, true, "Redare pusa pe pauza.")
            }
            "play_pause", "toggle" -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                Result(a, true, "Redare comutata.")
            }
            "next", "skip" -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                Result(a, true, "Urmatoarea piesa.")
            }
            "previous", "prev" -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                Result(a, true, "Piesa anterioara.")
            }
            "stop" -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_STOP)
                Result(a, true, "Redare oprita.")
            }
            "open", "search", "queue" -> {
                val r = when {
                    appName.contains("netflix") -> messenger.openNetflix(query)
                    appName.contains("youtube") -> messenger.openYouTubeMusic(query)
                    else -> messenger.openSpotify(query)
                }
                if (r is SendResult.Error) Result(a, false, r.reason)
                else Result(a, true, "Aplicatia deschisa${if (query != null) " cu cautarea: $query" else ""}.")
            }
            else -> Result(a, false, "Comanda necunoscuta: $command")
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    // ─── Smart home ──────────────────────────────────────────────────────────

    private fun homeControl(a: LumiAction): Result {
        val appName = a.params["app"]?.lowercase() ?: "google_home"
        val device  = a.params["device"] ?: ""
        val action  = a.params["action"] ?: ""
        val r = messenger.openGoogleHome()
        return if (r is SendResult.Error) Result(a, false, r.reason)
        else Result(a, true, "Google Home deschis. Controleaza manual: $device — $action.")
    }

    // ─── Health ──────────────────────────────────────────────────────────────

    private fun readHealth(a: LumiAction): Result {
        val appName = a.params["app"]?.lowercase() ?: "health_connect"
        val r = when {
            appName.contains("strava")         -> messenger.openStrava()
            appName.contains("myfitnesspal")   -> messenger.openMyFitnessPal()
            appName.contains("fit")            -> messenger.openGoogleFit()
            else                               -> messenger.openHealthConnect()
        }
        return if (r is SendResult.Error) Result(a, false, r.reason)
        else Result(a, true, "Aplicatia de sanatate deschisa.")
    }

    // ─── Banking ─────────────────────────────────────────────────────────────

    private fun readBalance(a: LumiAction): Result {
        val appName = a.params["app"]?.lowercase() ?: "revolut"
        val r = when {
            appName.contains("wise")    -> messenger.openWise()
            appName.contains("paypal")  -> messenger.openPayPal()
            appName.contains("bt") || appName.contains("banca") || appName.contains("transilvania") ->
                messenger.openBTpay()
            else -> messenger.openRevolut()
        }
        return if (r is SendResult.Error) Result(a, false, r.reason)
        else Result(a, true, "Aplicatia bancara deschisa.")
    }

    // ─── Shopping ────────────────────────────────────────────────────────────

    private fun shopSearch(a: LumiAction): Result {
        val appName = a.params["app"]?.lowercase() ?: "amazon"
        val query   = a.params["query"] ?: return Result(a, false, "Termen de cautare lipsa.")
        val r = when {
            appName.contains("ebay")       -> messenger.openEbaySearch(query)
            appName.contains("aliexpress") -> messenger.openAliExpressSearch(query)
            else                           -> messenger.openAmazonSearch(query)
        }
        return if (r is SendResult.Error) Result(a, false, r.reason)
        else Result(a, true, "Cautare deschisa pentru: $query")
    }

    private fun shopTrack(a: LumiAction): Result {
        val appName = a.params["app"]?.lowercase() ?: "amazon"
        val r = when {
            appName.contains("ebay") -> messenger.openEbaySearch("my orders")
            appName.contains("aliexpress") -> messenger.openAliExpressSearch("my orders")
            else -> messenger.openAmazonOrders()
        }
        return if (r is SendResult.Error) Result(a, false, r.reason)
        else Result(a, true, "Sectiunea de comenzi deschisa.")
    }

    // ─── Crypto balance ──────────────────────────────────────────────────────

    private fun readCrypto(a: LumiAction): Result {
        val appName = a.params["app"]?.lowercase() ?: "binance"
        val r = messenger.openCryptoApp(appName)
        return if (r is SendResult.Error) Result(a, false, r.reason)
        else Result(a, true, "Aplicatia crypto deschisa. Verifica soldul pe ecran.")
    }

    // ─── News ────────────────────────────────────────────────────────────────

    private fun fetchNews(a: LumiAction): Result {
        val topic = a.params["topic"]
        val r = messenger.openGoogleNews(topic)
        return if (r is SendResult.Error) Result(a, false, r.reason)
        else Result(a, true, "Stiri deschise${if (topic != null) " pentru: $topic" else ""}.")
    }

    // ─── Telegram ────────────────────────────────────────────────────────────

    private suspend fun sendTelegram(a: LumiAction): Result {
        val cName = a.params["contact"] ?: return Result(a, false, "Contact lipsa.")
        val msg   = a.params["message"] ?: return Result(a, false, "Mesaj lipsa.")
        val contact = contacts.findBestMatch(cName)
            ?: return Result(a, false, "Contactul \"$cName\" negasit.")
        if (!consent.request("Trimit mesaj Telegram lui ${contact.name}: \"$msg\". Confirmi?", getMode()))
            return Result(a, false, "Anulat.")
        return when (val r = messenger.sendTelegram(contact, msg)) {
            is SendResult.DeepLinkOpened -> {
                if (LumiAccessibilityService.isAvailable()) {
                    delay(3000)
                    val sent = LumiAccessibilityService.sendSocialMessage("org.telegram.messenger", contact.name, msg)
                    if (sent) Result(a, true, "Mesaj Telegram trimis lui ${contact.name}.")
                    else Result(a, true, "Telegram deschis cu mesajul pre-completat. Apasa Trimite.")
                } else {
                    Result(a, true, "Telegram deschis. Apasa Trimite.")
                }
            }
            is SendResult.Error -> Result(a, false, r.reason)
            else -> Result(a, true, "Telegram deschis.")
        }
    }

    // ─── Slack ───────────────────────────────────────────────────────────────

    private suspend fun sendSlack(a: LumiAction): Result {
        val channel = a.params["channel"] ?: a.params["contact"]
        val msg     = a.params["message"] ?: return Result(a, false, "Mesaj lipsa.")
        val target  = channel ?: "canal"
        if (!consent.request("Trimit pe Slack in $target: \"$msg\". Confirmi?", getMode()))
            return Result(a, false, "Anulat.")
        return when (val r = messenger.openSlack(channel, msg)) {
            is SendResult.Error -> Result(a, false, r.reason)
            else -> {
                if (LumiAccessibilityService.isAvailable()) {
                    delay(3000)
                    val sent = LumiAccessibilityService.sendSocialMessage("com.Slack", target, msg)
                    if (sent) Result(a, true, "Mesaj Slack trimis in $target.")
                    else Result(a, true, "Slack deschis. Apasa Trimite.")
                } else {
                    Result(a, true, "Slack deschis. Apasa Trimite.")
                }
            }
        }
    }

    // ─── Note apps ───────────────────────────────────────────────────────────

    private suspend fun writeNoteInApp(a: LumiAction): Result {
        val app     = a.params["app"] ?: return Result(a, false, "Aplicatia lipsa.")
        val title   = a.params["title"] ?: ""
        val content = a.params["content"] ?: return Result(a, false, "Continut lipsa.")
        val note    = notes.create(title, content)
        val appResult = notes.createInSpecificApp(app, title.ifBlank { note.title }, content)
        if (appResult == NoteAppResult.UI_OPENED && LumiAccessibilityService.isAvailable()) {
            delay(2500)
            LumiAccessibilityService.saveSamsungNote()
        }
        return Result(a, true, "Notita \"${note.title}\" salvata in $app.")
    }

    // ─── User memory ─────────────────────────────────────────────────────────

    private fun rememberFact(a: LumiAction): Result {
        val fact = a.params["fact"] ?: return Result(a, false, "Fapt lipsa.")
        val msg = userMemory.remember(fact)
        return Result(a, true, msg)
    }

    // ─── Battery saver ───────────────────────────────────────────────────────

    private fun setBatterySaver(a: LumiAction): Result {
        val enabled = a.params["enabled"]?.lowercase() == "true"
        return sysSettings.setBatterySaver(enabled).fold(
            onSuccess = {
                Result(a, true, if (enabled) "Economisire baterie activata." else "Economisire baterie dezactivata.")
            },
            onFailure = { Result(a, false, it.message ?: "Eroare economisire baterie.") }
        )
    }

    // ─── TTS speed ───────────────────────────────────────────────────────────

    private fun setTtsSpeed(a: LumiAction): Result {
        val direction = a.params["direction"]?.lowercase()
        val speedParam = a.params["speed"]?.toFloatOrNull()
        val current = tts?.speechRate ?: appSettings?.ttsSpeed ?: 1.0f
        val newRate: Float = when {
            speedParam != null -> speedParam.coerceIn(0.5f, 2.0f)
            direction?.contains("faster") == true || direction?.contains("repede") == true ||
                direction?.contains("rapid") == true -> (current * 1.25f).coerceIn(0.5f, 2.0f)
            direction?.contains("slower") == true || direction?.contains("incet") == true ||
                direction?.contains("lent") == true -> (current * 0.8f).coerceIn(0.5f, 2.0f)
            else -> 1.0f
        }
        appSettings?.ttsSpeed = newRate
        tts?.speechRate = newRate
        return Result(a, true, "Viteza vocii: ${(newRate * 100).toInt()}%.")
    }

    // ─── YouTube ─────────────────────────────────────────────────────────────

    private fun youtubeSearch(a: LumiAction): Result {
        val query = a.params["query"] ?: return Result(a, false, "Termen de cautare lipsa.")
        return when (val r = messenger.openYouTubeSearch(query)) {
            is SendResult.Error -> Result(a, false, r.reason)
            else -> Result(a, true, "YouTube deschis cu cautarea: $query")
        }
    }

    private fun youtubeWatchLater(a: LumiAction): Result {
        val query = a.params["query"]
        if (!query.isNullOrBlank()) {
            messenger.openYouTubeSearch(query)
            return Result(a, true, "YouTube deschis cu cautarea \"$query\". Apasa pe videoclip si salveaza-l in Watch Later.")
        }
        return when (val r = messenger.openYouTubeWatchLater()) {
            is SendResult.Error -> Result(a, false, r.reason)
            else -> Result(a, true, "Lista Watch Later deschisa.")
        }
    }

    private fun youtubeLibrary(a: LumiAction): Result {
        return when (val r = messenger.openYouTubeLibrary()) {
            is SendResult.Error -> Result(a, false, r.reason)
            else -> Result(a, true, "Biblioteca YouTube deschisa.")
        }
    }

    // ─── Food delivery ───────────────────────────────────────────────────────

    private suspend fun foodDelivery(a: LumiAction): Result {
        val app           = a.params["app"]?.lowercase() ?: "ubereats"
        val food          = a.params["food"] ?: return Result(a, false, "Aliment lipsa.")
        val restaurant    = a.params["restaurant"]
        val customization = a.params["customization"]
        val appLabel = when {
            app.contains("doordash")  -> "DoorDash"
            app.contains("deliveroo") -> "Deliveroo"
            else -> "Uber Eats"
        }
        val customNote = if (!customization.isNullOrBlank()) " (personalizare: $customization)" else ""
        if (!consent.request("Deschid $appLabel si caut $food$customNote. Confirmi?", getMode()))
            return Result(a, false, "Anulat.")
        return when (val r = messenger.openFoodDelivery(app, food, restaurant)) {
            is SendResult.Error -> Result(a, false, r.reason)
            else -> Result(a, true, "$appLabel deschis cu cautarea \"$food\"$customNote. Finalizeaza comanda si plata in aplicatie.")
        }
    }

    // ─── Ride sharing ────────────────────────────────────────────────────────

    private suspend fun rideshare(a: LumiAction): Result {
        val app         = a.params["app"]?.lowercase() ?: "uber"
        val destination = a.params["destination"] ?: return Result(a, false, "Destinatie lipsa.")
        val pickup      = a.params["pickup"]
        val rideType    = a.params["ride_type"]
        val appLabel    = if (app.contains("lyft")) "Lyft" else "Uber"
        val rideLabel   = rideType?.let { " ($it)" } ?: ""
        if (!consent.request("Deschid $appLabel catre $destination$rideLabel. Confirmi?", getMode()))
            return Result(a, false, "Anulat.")
        val r = if (app.contains("lyft"))
            messenger.openLyft(pickup, destination, rideType)
        else
            messenger.openUber(pickup, destination)
        return when (r) {
            is SendResult.Error -> Result(a, false, r.reason)
            else -> Result(a, true, "$appLabel deschis catre $destination. Confirma comanda in aplicatie.")
        }
    }

    // ─── Bluetooth devices ───────────────────────────────────────────────────

    private fun btListDevices(a: LumiAction): Result {
        val list = btDeviceManager.formatDeviceList()
        return Result(a, true, list)
    }

    private fun btConnect(a: LumiAction): Result {
        val name = a.params["device"] ?: return Result(a, false, "Nume dispozitiv lipsa.")
        val msg  = btDeviceManager.guideConnect(name, lumiDeviceAddress)
        return Result(a, true, msg)
    }

    private fun btDisconnect(a: LumiAction): Result {
        val name = a.params["device"] ?: return Result(a, false, "Nume dispozitiv lipsa.")
        val msg  = btDeviceManager.guideDisconnect(name, lumiDeviceAddress)
        return Result(a, true, msg)
    }

    private fun btPair(a: LumiAction): Result {
        val name = a.params["device"] ?: return Result(a, false, "Nume dispozitiv lipsa.")
        val msg  = btDeviceManager.guidePair(name)
        return Result(a, true, msg)
    }

    // ─── Gallery ─────────────────────────────────────────────────────────────

    private suspend fun gallerySearch(a: LumiAction): Result {
        val helper = gallerySearchHelper ?: return Result(a, false, "Galerie indisponibila.")
        val settings = appSettings ?: return Result(a, false, "Setari indisponibile.")
        val query    = a.params["query"]
        val fromDate = a.params["from_date"]
        val toDate   = a.params["to_date"]
        val limit    = a.params["limit"]?.toIntOrNull() ?: 10
        val offset   = a.params["offset"]?.toIntOrNull() ?: 0

        val all      = helper.queryRecent(GallerySearchHelper.MAX_SCAN)
        val fromMs   = GallerySearchHelper.parseDateString(fromDate)
        val toMs     = GallerySearchHelper.parseDateString(toDate)
        val filtered = helper.filterByDateRange(all, fromMs, toMs)

        val pool = if (offset > 0) filtered.drop(offset) else filtered

        val results = if (!query.isNullOrBlank()) {
            val client = com.lumi.app.ai.GeminiClient(settings.openRouterApiKey, settings.openRouterBaseUrl)
            helper.findByVision(pool, query, client, maxResults = limit)
        } else {
            pool.take(limit)
        }

        if (results.isEmpty()) return Result(a, true, "Nu s-au gasit imagini.")
        val ids = results.map { it.id }
        return Result(a, true, helper.formatSummary(results), ids)
    }

    private suspend fun sendImage(a: LumiAction): Result {
        val app        = a.params["app"]?.lowercase() ?: "whatsapp"
        val imageIdStr = a.params["image_id"]
        val usePending = a.params["use_pending"]?.lowercase() == "true"
        val cName      = a.params["contact"]

        val imageUri: Uri = when {
            usePending && currentImageBase64 != null -> {
                val file = gallerySearchHelper?.saveBase64ToCache(currentImageBase64!!)
                    ?: return Result(a, false, "Nu s-a putut salva imaginea temporar.")
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            imageIdStr != null -> {
                val id = imageIdStr.toLongOrNull()
                    ?: return Result(a, false, "ID imagine invalid.")
                gallerySearchHelper?.getUri(id)
                    ?: return Result(a, false, "Gallery helper indisponibil.")
            }
            else -> return Result(a, false, "Nicio imagine specificata (image_id sau use_pending=true).")
        }

        val appLabel   = when { app.contains("instagram") -> "Instagram"; app.contains("telegram") -> "Telegram"; else -> "WhatsApp" }
        val contactInfo = if (cName != null) " lui $cName" else ""
        if (!consent.request("Trimit imaginea pe $appLabel$contactInfo. Confirmi?", getMode()))
            return Result(a, false, "Anulat.")

        val contact = if (cName != null) contacts.findBestMatch(cName) else null
        val result = when {
            app.contains("instagram") -> messenger.sendInstagramImage(imageUri)
            app.contains("telegram")  -> messenger.sendTelegramImage(contact, imageUri)
            else -> if (contact != null) messenger.sendWhatsAppImage(contact, imageUri)
                    else messenger.shareImageToApp(imageUri, MessageSender.WHATSAPP_PACKAGE, "WhatsApp")
        }

        return when (result) {
            is SendResult.Error -> Result(a, false, result.reason)
            else -> {
                if (!app.contains("instagram") && !app.contains("telegram") &&
                    contact != null && LumiAccessibilityService.isAvailable()) {
                    delay(3500)
                    val contactSelected = LumiAccessibilityService.tapWhatsAppShareContact(contact.name)
                    if (contactSelected) {
                        delay(2000)
                        val sent = LumiAccessibilityService.tapWhatsAppImageSend()
                        if (sent) Result(a, true, "Imagine trimisa pe $appLabel$contactInfo.")
                        else Result(a, true, "$appLabel deschis cu imaginea. Apasa Trimite manual.")
                    } else {
                        Result(a, true, "$appLabel deschis cu imaginea. Selecteaza ${contact.name} si apasa Trimite.")
                    }
                } else {
                    Result(a, true, "Imagine trimisa pe $appLabel$contactInfo.")
                }
            }
        }
    }
}
