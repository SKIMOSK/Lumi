package com.lumi.app.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lumi.app.consent.ConsentManager
import com.lumi.app.consent.ConsentMode
import com.lumi.app.contacts.ContactsHelper
import com.lumi.app.messaging.MessageSender
import com.lumi.app.messaging.SendResult
import com.lumi.app.notes.NoteAppResult
import com.lumi.app.notes.NotesHelper
import com.lumi.app.system.SystemSettingsHelper
import com.lumi.app.timer.TimerManager
import com.lumi.app.whatsapp.LumiAccessibilityService
import kotlinx.coroutines.delay
import java.util.Calendar

class ActionExecutor(
    private val context: Context,
    private val timers: TimerManager,
    private val consent: ConsentManager,
    private val contacts: ContactsHelper,
    private val messenger: MessageSender,
    private val notes: NotesHelper,
    private val sysSettings: SystemSettingsHelper,
    private val getMode: () -> ConsentMode
) {
    data class Result(val action: LumiAction, val success: Boolean, val message: String)

    suspend fun executeAll(actions: List<LumiAction>): List<Result> =
        actions.map { runCatching { execute(it) }.getOrElse { e -> Result(it, false, "Eroare: ${e.message}") } }

    private suspend fun execute(a: LumiAction): Result = when (a.type.uppercase()) {
        "SET_TIMER"       -> setTimer(a)
        "SET_STOPWATCH"   -> setStopwatch(a)
        "SET_ALARM"       -> setAlarm(a)
        "PAUSE_TIMER", "PAUSE_STOPWATCH"   -> pauseTimer(a)
        "RESUME_TIMER", "RESUME_STOPWATCH" -> resumeTimer(a)
        "CANCEL_TIMER", "CANCEL_STOPWATCH", "CANCEL_ALARM" -> cancelTimer(a)
        "RESET_TIMER", "RESET_STOPWATCH"   -> resetTimer(a)
        "SEND_WHATSAPP"   -> sendWhatsApp(a)
        "SEND_SMS"        -> sendSms(a)
        "CALL"            -> call(a)
        "WRITE_NOTE"      -> writeNote(a)
        "SET_BRIGHTNESS"  -> setBrightness(a)
        "SET_VOLUME"      -> setVolume(a)
        "SET_DND"         -> setDND(a)
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
                // WhatsApp was opened with the message pre-filled.
                // If the accessibility service is active, wait for WhatsApp to load then tap Send.
                if (LumiAccessibilityService.isAvailable()) {
                    delay(2500)
                    val sent = LumiAccessibilityService.sendCurrentMessage(msg)
                    if (sent) Result(a, true, "Mesaj trimis lui ${contact.name}.")
                    else Result(a, true, "WhatsApp deschis. Apasă Trimite manual.")
                } else {
                    Result(a, true, "WhatsApp deschis cu mesajul pre-completat. Apasă Trimite.")
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
            is SendResult.DeepLinkOpened -> Result(a, true, "SMS deschis. Apasă Trimite.")
            is SendResult.Error -> Result(a, false, r.reason)
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
            // If Samsung Notes (or Keep) opened its compose UI, use accessibility to auto-save
            if (appResult == NoteAppResult.UI_OPENED && LumiAccessibilityService.isAvailable()) {
                delay(2500)
                LumiAccessibilityService.saveSamsungNote()
            }
            Result(a, true, "Notita \"${note.title}\" salvata.")
        }
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
}
