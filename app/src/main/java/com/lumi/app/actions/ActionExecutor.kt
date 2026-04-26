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
import com.lumi.app.files.FileHelper
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
import java.io.File

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
    private val gallerySearchHelper: GallerySearchHelper? = null,
    private val documentHelper: com.lumi.app.system.DocumentHelper
) {
    data class Result(val action: LumiAction, val success: Boolean, val message: String, val galleryImageIds: List<Long>? = null)

    private var currentImageBase64: String? = null
    private var currentFileUri: Uri? = null
    private val fileHelper = FileHelper(context)
    private var lastBatchGalleryIds: List<Long>? = null

    suspend fun executeAll(actions: List<LumiAction>, imageBase64: String? = null, fileUri: Uri? = null): List<Result> {
        currentImageBase64 = imageBase64
        currentFileUri = fileUri
        lastBatchGalleryIds = null
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
        "DELETE_NOTE"          -> deleteNote(a)
        "REMEMBER_FACT"        -> rememberFact(a)
        "NAVIGATE_MAPS"        -> navigateMaps(a)
        "NAVIGATE_WAZE"        -> navigateWaze(a)
        "CONNECT_VPN"          -> toggleVpn(a, true)
        "DISCONNECT_VPN"       -> toggleVpn(a, false)
        "CREATE_EVENT"         -> createEvent(a)
        "READ_CALENDAR"        -> readCalendar(a)
        "DELETE_EVENT"         -> deleteEvent(a)
        "UPDATE_EVENT"         -> updateEvent(a)
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
        "FORWARD_FILE"         -> forwardFile(a)
        "CREATE_FORWARD_FILE"  -> createAndForwardFile(a)
        "SEND_FILE"            -> sendFile(a)
        "EDIT_FILE"            -> editFile(a)
        "REPLY_NOTIFICATION"   -> replyNotification(a)
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
        try {
            val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, name)
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, h)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, m)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return Result(a, true, "Alarma \"$name\" setata la $time in aplicatia ceas.")
        } catch (e: Exception) {
            return Result(a, false, "Nu s-a putut seta alarma in aplicatia ceas.")
        }
    }

    private fun resolveTimer(a: LumiAction) =
        timers.resolve(a.params["name"] ?: a.params["index"] ?: a.params["timer_index"])

    private fun pauseTimer(a: LumiAction): Result {
        val t = resolveTimer(a) ?: return Result(a, false, "Timer negasit.")
        timers.pause(t.id)
        return Result(a, true, "Timer \"${t.name}\" in pauza.")
    }

    private fun resumeTimer(a: LumiAction): Result {
        val t = resolveTimer(a) ?: return Result(a, false, "Timer negasit.")
        timers.resume(t.id)
        return Result(a, true, "Timer \"${t.name}\" reluat.")
    }

    private fun cancelTimer(a: LumiAction): Result {
        val t = resolveTimer(a) ?: return Result(a, false, "Timer negasit.")
        timers.cancel(t.id)
        return Result(a, true, "Timer \"${t.name}\" anulat.")
    }

    private fun resetTimer(a: LumiAction): Result {
        val t = resolveTimer(a) ?: return Result(a, false, "Timer negasit.")
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
        val body     = a.params["body"] ?: a.params["message"] ?: ""
        val attachQuery   = a.params["attachment_query"]
        val attachPending = a.params["attach_pending"]?.lowercase() == "true"
        // Resolve email address: use raw if it contains @, otherwise look up contact
        val to = if (toParam.contains("@")) toParam else {
            contacts.findBestMatch(toParam)?.emails?.firstOrNull() ?: toParam
        }

        // Attachment branch — find or use pending file, then ACTION_SEND
        val attachUri: Uri? = when {
            attachPending && currentFileUri != null -> currentFileUri
            !attachQuery.isNullOrBlank() -> {
                val f = documentHelper.findRecentFile(attachQuery)
                    ?: return Result(a, false, "Fisierul \"$attachQuery\" nu a fost gasit pe telefon.")
                try {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                } catch (e: Exception) {
                    return Result(a, false, "Nu am putut atasa fisierul: ${e.message}")
                }
            }
            else -> null
        }

        val descSubject = subject.ifBlank { "(fara subiect)" }
        val attachInfo = if (attachUri != null) " cu atasament" else ""
        if (!consent.request("Trimit email la $to: \"$descSubject\"$attachInfo. Confirmi?", getMode()))
            return Result(a, false, "Anulat.")

        // Plain email — use existing mailto path
        if (attachUri == null) {
            if (body.isBlank()) return Result(a, false, "Continut email lipsa.")
            return when (messenger.composeEmail(to, subject, body)) {
                is SendResult.DeepLinkOpened -> {
                    if (LumiAccessibilityService.isAvailable()) {
                        delay(2500)
                        LumiAccessibilityService.sendGmailAfterCompose()
                        Result(a, true, "Email trimis la $to.")
                    } else Result(a, true, "Email deschis. Apasa Trimite.")
                }
                is SendResult.Error -> Result(a, false, "Nu s-a putut deschide emailul.")
                else -> Result(a, true, "Email deschis.")
            }
        }

        // Email with attachment — share document, prefer Gmail then chooser
        val r = messenger.shareDocumentToApp(
            fileUri = attachUri, pkg = "com.google.android.gm",
            appName = "Gmail", subject = subject, body = body, emailTo = to
        )
        return when (r) {
            is SendResult.Error -> Result(a, false, r.reason)
            else -> Result(a, true, "Email pregatit catre $to cu atasament. Apasa Trimite.")
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
            val ok = notes.update(id, title.ifBlank { null }, content)
            if (ok) Result(a, true, "Notita actualizata.")
            else Result(a, false, "Notita cu ID $id nu a fost gasita.")
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

    private fun deleteNote(a: LumiAction): Result {
        val id = a.params["id"]
        val title = a.params["title"]
        val target = when {
            id != null -> id
            title != null -> notes.findByTitle(title)?.id
                ?: return Result(a, false, "Notita \"$title\" negasita.")
            else -> return Result(a, false, "ID sau titlu notita lipsa.")
        }
        return if (notes.delete(target)) Result(a, true, "Notita stearsa.")
        else Result(a, false, "Notita cu ID $target nu a fost gasita.")
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

    private fun deleteEvent(a: LumiAction): Result {
        val cal = CalendarHelper(context)
        val id = a.params["id"]?.toLongOrNull()
        val title = a.params["title"]
        val targetId = id ?: title?.let { cal.findByTitle(it)?.id }
            ?: return Result(a, false, "ID sau titlu eveniment lipsa.")
        return if (cal.delete(targetId)) Result(a, true, "Eveniment sters din calendar.")
        else Result(a, false, "Nu s-a putut sterge evenimentul $targetId.")
    }

    private fun updateEvent(a: LumiAction): Result {
        val cal = CalendarHelper(context)
        val id = a.params["id"]?.toLongOrNull()
        val lookupTitle = a.params["lookup_title"] ?: a.params["find_title"]
        val targetId = id ?: lookupTitle?.let { cal.findByTitle(it)?.id }
            ?: return Result(a, false, "ID sau lookup_title eveniment lipsa.")
        val newTitle = a.params["new_title"] ?: a.params["title"]
        val desc = a.params["description"]
        val loc = a.params["location"]
        val startMs = parseDateTime(a.params["start_datetime"])
        val endMs = parseDateTime(a.params["end_datetime"])
        return if (cal.update(targetId, newTitle, desc, loc, startMs, endMs))
            Result(a, true, "Eveniment actualizat.")
        else Result(a, false, "Nu s-a putut actualiza evenimentul $targetId.")
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
        val direction = a.params["direction"]?.lowercase()
        val step = a.params["step"]?.toIntOrNull() ?: 20

        val level: Int = when {
            direction == "up" || direction == "increase" || direction == "tare" || direction == "creste" -> {
                val current = sysSettings.getVolume(stream)
                (current + step).coerceAtMost(100)
            }
            direction == "down" || direction == "decrease" || direction == "incet" || direction == "scade" -> {
                val current = sysSettings.getVolume(stream)
                (current - step).coerceAtLeast(0)
            }
            direction == "mute" || direction == "mut" || direction == "silent" || direction == "silentios" -> 0
            direction == "max" || direction == "maximum" -> 100
            else -> a.params["level"]?.toIntOrNull() ?: return Result(a, false, "Nivel sau directie lipsa.")
        }
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
        lastBatchGalleryIds = ids  // enables same-request SEND_IMAGE chaining
        return Result(a, true, helper.formatSummary(results), ids)
    }

    private suspend fun sendImage(a: LumiAction): Result {
        val app              = a.params["app"]?.lowercase() ?: "whatsapp"
        val imageIdStr       = a.params["image_id"]
        val usePending       = a.params["use_pending"]?.lowercase() == "true"
        val useGalleryResult = a.params["use_gallery_result"]?.toIntOrNull()
        val cName            = a.params["contact"]

        val imageUri: Uri = when {
            usePending && currentImageBase64 != null -> {
                val file = gallerySearchHelper?.saveBase64ToCache(currentImageBase64!!)
                    ?: return Result(a, false, "Nu s-a putut salva imaginea temporar.")
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            useGalleryResult != null -> {
                val ids = lastBatchGalleryIds
                    ?: return Result(a, false, "Nicio cautare anterioara. Cauta mai intai cu GALLERY_SEARCH.")
                val id = ids.getOrNull(useGalleryResult)
                    ?: return Result(a, false, "Nu exista imaginea cu indicele $useGalleryResult din cautare.")
                gallerySearchHelper?.getUri(id)
                    ?: return Result(a, false, "Gallery helper indisponibil.")
            }
            imageIdStr != null -> {
                val id = imageIdStr.toLongOrNull()
                    ?: return Result(a, false, "ID imagine invalid.")
                gallerySearchHelper?.getUri(id)
                    ?: return Result(a, false, "Gallery helper indisponibil.")
            }
            else -> return Result(a, false, "Nicio imagine specificata (image_id, use_gallery_result sau use_pending=true).")
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
                    
                    val sent = LumiAccessibilityService.tapWhatsAppImageSend()
                    if (sent) Result(a, true, "Imagine trimisa pe $appLabel$contactInfo.")
                    else Result(a, true, "$appLabel deschis cu imaginea. Apasa Trimite manual.")
                } else {
                    Result(a, true, "Imagine trimisa pe $appLabel$contactInfo.")
                }
            }
        }
    }

    private suspend fun forwardFile(a: LumiAction): Result {
        val app = a.params["app"]?.lowercase() ?: return Result(a, false, "Aplicatia lipsa.")
        val cName = a.params["contact"] ?: return Result(a, false, "Contactul lipsa.")
        val query = a.params["query"] ?: return Result(a, false, "Nume fisier lipsa.")

        val file = documentHelper.findRecentFile(query)
            ?: return Result(a, false, "Fisierul nu a putut fi gasit in telefon.")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        return forwardFileUri(a, uri, app, cName)
    }

    private suspend fun createAndForwardFile(a: LumiAction): Result {
        val app = a.params["app"]?.lowercase() ?: return Result(a, false, "Aplicatia lipsa.")
        val cName = a.params["contact"] ?: return Result(a, false, "Contactul lipsa.")
        val filename = a.params["filename"] ?: return Result(a, false, "Nume fisier lipsa.")
        val content = a.params["new_content"] ?: return Result(a, false, "Continut lipsa.")

        val originalFile = documentHelper.findRecentFile(filename)
        val destFile = if (originalFile != null) {
            documentHelper.writeTextToNewFile(originalFile, content)
        } else {
            val dest = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), filename + "_corectat.txt")
            dest.writeText(content)
            dest
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
        return forwardFileUri(a, uri, app, cName)
    }

    private suspend fun forwardFileUri(a: LumiAction, uri: Uri, app: String, cName: String): Result {
        val appLabel = app.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
        if (!consent.request("Trimit fisier pe $appLabel catre $cName. Confirmi?", getMode()))
            return Result(a, false, "Anulat.")

        val contact = contacts.findBestMatch(cName)
        val subject = a.params["subject"]
        val body    = a.params["body"] ?: a.params["message"]
        val isEmail = app.contains("email") || app.contains("gmail") || app.contains("outlook")
        // For email destinations, resolve the email address from contact name if needed
        val emailTo = if (isEmail) {
            if (cName.contains("@")) cName
            else contact?.emails?.firstOrNull()
        } else null
        if (isEmail && emailTo == null)
            return Result(a, false, "Nu am gasit adresa de email pentru $cName.")

        val pkg = when {
            app.contains("whatsapp") -> "com.whatsapp"
            app.contains("telegram") -> "org.telegram.messenger"
            app.contains("discord") -> "com.discord"
            app.contains("slack") -> "com.Slack"
            app.contains("email") || app.contains("gmail") -> "com.google.android.gm"
            app.contains("outlook") -> "com.microsoft.office.outlook"
            else -> null
        }

        val result = messenger.shareDocumentToApp(
            fileUri = uri, pkg = pkg, appName = appLabel,
            subject = subject, body = body, emailTo = emailTo
        )

        return when (result) {
            is SendResult.Error -> Result(a, false, result.reason)
            else -> {
                if (pkg == "com.whatsapp" && contact != null && LumiAccessibilityService.isAvailable()) {
                    delay(3500)
                    LumiAccessibilityService.tapWhatsAppShareContact(contact.name)
                }
                if (isEmail) Result(a, true, "Email cu atasament pregatit catre ${emailTo ?: cName}. Apasa Trimite.")
                else Result(a, true, "Fisier pregatit pentru trimitere pe $appLabel. Finalizeaza manual daca e nevoie.")
            }
        }
    }

    // ─── File (URI-based — from attach button) ────────────────────────────────

    private suspend fun sendFile(a: LumiAction): Result {
        val uri = currentFileUri
            ?: return Result(a, false, "Niciun fisier atasat. Ataseaza un fisier si reincearca.")
        val app     = a.params["app"]?.lowercase() ?: "whatsapp"
        val cName   = a.params["contact"]
        val name    = fileHelper.getDisplayName(uri)
        val subject = a.params["subject"]
        val body    = a.params["body"] ?: a.params["message"]
        val appLabel = when {
            app.contains("telegram")  -> "Telegram"
            app.contains("instagram") -> "Instagram"
            app.contains("email") || app.contains("gmail") -> "Gmail"
            app.contains("outlook") -> "Outlook"
            app.contains("discord") -> "Discord"
            app.contains("slack")   -> "Slack"
            else -> "WhatsApp"
        }
        val contactInfo = if (cName != null) " lui $cName" else ""
        if (!consent.request("Trimit fisierul \"$name\" pe $appLabel$contactInfo. Confirmi?", getMode()))
            return Result(a, false, "Anulat.")

        val isEmail = appLabel == "Gmail" || appLabel == "Outlook"
        val emailTo = if (isEmail && cName != null) {
            if (cName.contains("@")) cName
            else contacts.findBestMatch(cName)?.emails?.firstOrNull()
        } else null

        val pkg = when (appLabel) {
            "Telegram"  -> "org.telegram.messenger"
            "Instagram" -> "com.instagram.android"
            "Gmail"     -> "com.google.android.gm"
            "Outlook"   -> "com.microsoft.office.outlook"
            "Discord"   -> "com.discord"
            "Slack"     -> "com.Slack"
            else        -> MessageSender.WHATSAPP_PACKAGE
        }

        val r = messenger.shareDocumentToApp(
            fileUri = uri, pkg = pkg, appName = appLabel,
            subject = subject, body = body, emailTo = emailTo
        )
        return when (r) {
            is SendResult.Error -> Result(a, false, r.reason)
            else -> Result(a, true, "Fisierul \"$name\" deschis in $appLabel$contactInfo.")
        }
    }

    private fun editFile(a: LumiAction): Result {
        val uri = currentFileUri
            ?: return Result(a, false, "Niciun fisier atasat pentru editare.")
        val newContent = a.params["new_content"]
            ?: return Result(a, false, "Continut nou lipsa.")
        val name = fileHelper.getDisplayName(uri)
        return if (fileHelper.writeText(uri, newContent)) {
            Result(a, true, "Fisierul \"$name\" a fost actualizat.")
        } else {
            Result(a, false, "Nu s-a putut salva fisierul \"$name\".")
        }
    }

    // ─── Notification reply ──────────────────────────────────────────────────

    private suspend fun replyNotification(a: LumiAction): Result {
        val msg = a.params["message"] ?: return Result(a, false, "Mesaj lipsa.")
        val key = a.params["notification_key"]
        val contact = a.params["contact"]
        val app = a.params["app"]?.lowercase()
        val pkg = when {
            app == null -> null
            app.contains("whatsapp") -> "com.whatsapp"
            app.contains("telegram") -> "org.telegram.messenger"
            app.contains("messenger") || app.contains("facebook") -> "com.facebook.orca"
            app.contains("signal") -> "org.thoughtcrime.securesms"
            app.contains("instagram") -> "com.instagram.android"
            app.contains("sms") || app.contains("messages") -> "com.google.android.apps.messaging"
            else -> null
        }
        val notif = when {
            key != null -> com.lumi.app.notifications.LumiNotificationService.findByKey(key)
            pkg != null -> com.lumi.app.notifications.LumiNotificationService.findReplyable(pkg, contact)
            else -> com.lumi.app.notifications.LumiNotificationService.findLastReplyable(contact)
        } ?: return Result(a, false, "Nu am gasit notificare la care sa raspund.")
        if (notif.directReply == null)
            return Result(a, false, "Notificarea nu accepta raspuns rapid. Deschide aplicatia manual.")
        if (!consent.request("Raspund in ${notif.appName} catre ${notif.title}: \"$msg\". Confirmi?", getMode()))
            return Result(a, false, "Anulat.")
        val ok = com.lumi.app.notifications.LumiNotificationService.sendReply(context, notif, msg)
        return if (ok) Result(a, true, "Raspuns trimis in ${notif.appName} catre ${notif.title}.")
        else Result(a, false, "Nu s-a putut trimite raspunsul.")
    }
}
