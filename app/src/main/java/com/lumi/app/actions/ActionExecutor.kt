package com.lumi.app.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lumi.app.consent.ConsentManager
import com.lumi.app.consent.ConsentMode
import com.lumi.app.contacts.ContactsHelper
import com.lumi.app.messaging.MessageSender
import com.lumi.app.messaging.SendResult
import com.lumi.app.timer.TimerManager
import java.util.Calendar

class ActionExecutor(
    private val context: Context,
    private val timers: TimerManager,
    private val consent: ConsentManager,
    private val contacts: ContactsHelper,
    private val messenger: MessageSender,
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
        else -> Result(a, false, "Acțiune necunoscută: ${a.type}")
    }

    // ─── Timers ──────────────────────────────────────────────────────────────

    private fun setTimer(a: LumiAction): Result {
        val name = a.params["name"] ?: "Timer"
        val secs = a.params["duration_seconds"]?.toLongOrNull()
            ?: return Result(a, false, "Durată lipsă pentru timer.")
        timers.setTimer(name, secs)
        return Result(a, true, "Timer \"$name\" setat: ${timers.fmtSecs(secs)}.")
    }

    private fun setStopwatch(a: LumiAction): Result {
        val name = a.params["name"] ?: "Cronometru"
        timers.startStopwatch(name)
        return Result(a, true, "Cronometru \"$name\" pornit.")
    }

    private fun setAlarm(a: LumiAction): Result {
        val name = a.params["name"] ?: "Alarmă"
        val time = a.params["time_24h"] ?: return Result(a, false, "Ora lipsă.")
        val parts = time.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return Result(a, false, "Oră invalidă.")
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
            if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        timers.setAlarm(name, cal.timeInMillis)
        return Result(a, true, "Alarmă \"$name\" setată la $time.")
    }

    private fun pauseTimer(a: LumiAction): Result {
        val t = timers.getByName(a.params["name"] ?: "") ?: return Result(a, false, "Timer negăsit.")
        timers.pause(t.id)
        return Result(a, true, "Timer \"${t.name}\" în pauză.")
    }

    private fun resumeTimer(a: LumiAction): Result {
        val t = timers.getByName(a.params["name"] ?: "") ?: return Result(a, false, "Timer negăsit.")
        timers.resume(t.id)
        return Result(a, true, "Timer \"${t.name}\" reluat.")
    }

    private fun cancelTimer(a: LumiAction): Result {
        val t = timers.getByName(a.params["name"] ?: "") ?: return Result(a, false, "Timer negăsit.")
        timers.cancel(t.id)
        return Result(a, true, "Timer \"${t.name}\" anulat.")
    }

    private fun resetTimer(a: LumiAction): Result {
        val t = timers.getByName(a.params["name"] ?: "") ?: return Result(a, false, "Timer negăsit.")
        timers.reset(t.id)
        return Result(a, true, "Timer \"${t.name}\" resetat.")
    }

    // ─── Messaging ───────────────────────────────────────────────────────────

    private suspend fun sendWhatsApp(a: LumiAction): Result {
        val cName = a.params["contact"] ?: return Result(a, false, "Contact lipsă.")
        val msg   = a.params["message"] ?: return Result(a, false, "Mesaj lipsă.")
        val exact = a.params["exact"] == "true"

        val contact = contacts.resolveAlias(cName) ?: contacts.searchByName(cName).firstOrNull()
            ?: return Result(a, false, "Contactul \"$cName\" negăsit.")

        val prompt = if (exact)
            "Trimit WhatsApp lui ${contact.name}: \"$msg\". Confirmi?"
        else
            "Urmează să trimit lui ${contact.name}: \"$msg\". Confirmi?"

        if (!consent.request(prompt, getMode())) return Result(a, false, "Anulat de utilizator.")

        return when (val r = messenger.sendWhatsApp(contact, msg)) {
            is SendResult.Success -> Result(a, true, "Mesaj trimis lui ${contact.name}.")
            is SendResult.Error   -> Result(a, false, r.reason)
        }
    }

    private suspend fun sendSms(a: LumiAction): Result {
        val cName = a.params["contact"] ?: return Result(a, false, "Contact lipsă.")
        val msg   = a.params["message"] ?: return Result(a, false, "Mesaj lipsă.")

        val contact = contacts.resolveAlias(cName) ?: contacts.searchByName(cName).firstOrNull()
            ?: return Result(a, false, "Contactul \"$cName\" negăsit.")

        if (!consent.request("Trimit SMS lui ${contact.name}: \"$msg\". Confirmi?", getMode()))
            return Result(a, false, "Anulat.")

        return when (val r = messenger.sendSms(contact, msg)) {
            is SendResult.Success -> Result(a, true, "SMS trimis lui ${contact.name}.")
            is SendResult.Error   -> Result(a, false, r.reason)
        }
    }

    private suspend fun call(a: LumiAction): Result {
        val cName = a.params["contact"] ?: return Result(a, false, "Contact lipsă.")
        val contact = contacts.resolveAlias(cName) ?: contacts.searchByName(cName).firstOrNull()
            ?: return Result(a, false, "Contactul \"$cName\" negăsit.")
        val phone = contact.phoneNumbers.firstOrNull() ?: return Result(a, false, "Niciun număr.")

        if (!consent.request("Suni pe ${contact.name}. Confirmi?", getMode()))
            return Result(a, false, "Apel anulat.")

        return try {
            context.startActivity(Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phone")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            Result(a, true, "Sun pe ${contact.name}…")
        } catch (e: Exception) {
            Result(a, false, "Eroare apel: ${e.message}")
        }
    }
}
