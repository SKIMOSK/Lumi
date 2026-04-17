package com.lumi.app.messaging

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log
import com.lumi.app.contacts.Contact
import com.lumi.app.notifications.LumiNotificationService

sealed class SendResult {
    object Success : SendResult()
    data class Error(val reason: String) : SendResult()
}

class MessageSender(private val context: Context) {

    companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val TAG = "MessageSender"
    }

    fun isWhatsAppInstalled() = try {
        context.packageManager.getPackageInfo(WHATSAPP_PACKAGE, 0); true
    } catch (e: Exception) { false }

    /**
     * Send WhatsApp message.
     * First tries silent notification-reply (smartwatch style, no app launch).
     * Falls back to deep-link (opens WhatsApp pre-filled, user taps Send).
     */
    fun sendWhatsApp(contact: Contact, message: String): SendResult {
        if (!isWhatsAppInstalled()) return SendResult.Error("WhatsApp nu este instalat.")

        // Prefer silent reply via notification RemoteInput
        val notif = LumiNotificationService.findReplyable(WHATSAPP_PACKAGE, contact.name)
        if (notif?.directReply != null) {
            return trySilentReply(notif.directReply!!, message)
        }

        // Fallback: deep link
        val phone = contact.phoneNumbers.firstOrNull()
            ?: return SendResult.Error("Contactul nu are număr de telefon.")
        val clean = phone.replace(Regex("[^\\d+]"), "")
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://api.whatsapp.com/send?phone=$clean&text=${Uri.encode(message)}")
                setPackage(WHATSAPP_PACKAGE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            SendResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "WhatsApp deep-link failed", e)
            SendResult.Error("Nu s-a putut deschide WhatsApp: ${e.message}")
        }
    }

    /** Silently reply to an existing notification (smartwatch style — no app launch, no unlock needed). */
    fun trySilentReply(reply: com.lumi.app.notifications.DirectReply, message: String): SendResult {
        return try {
            val intent = Intent()
            android.app.RemoteInput.addResultsToIntent(
                arrayOf(reply.remoteInput),
                intent,
                Bundle().apply { putCharSequence(reply.resultKey, message) }
            )
            reply.replyPendingIntent.send(context, 0, intent)
            SendResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Silent reply failed", e)
            SendResult.Error("Eroare la trimitere silențioasă: ${e.message}")
        }
    }

    fun sendSms(contact: Contact, message: String): SendResult {
        val phone = contact.phoneNumbers.firstOrNull()
            ?: return SendResult.Error("Contactul nu are număr.")
        return try {
            @Suppress("DEPRECATION")
            val mgr = SmsManager.getDefault()
            mgr.sendMultipartTextMessage(phone, null, mgr.divideMessage(message), null, null)
            SendResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "SMS failed", e)
            SendResult.Error("Eroare SMS: ${e.message}")
        }
    }

    fun composeSms(contact: Contact, message: String): SendResult {
        val phone = contact.phoneNumbers.firstOrNull()
            ?: return SendResult.Error("Contactul nu are număr.")
        return try {
            context.startActivity(Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phone")
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            SendResult.Success
        } catch (e: Exception) {
            SendResult.Error("Nu s-a putut deschide SMS.")
        }
    }
}
