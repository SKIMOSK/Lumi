package com.lumi.app.messaging

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import com.lumi.app.contacts.Contact

sealed class SendResult {
    object Success : SendResult()
    data class Error(val reason: String) : SendResult()
}

class MessageSender(private val context: Context) {

    companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"
        const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
        private const val TAG = "MessageSender"
    }

    fun isWhatsAppInstalled(): Boolean =
        try { context.packageManager.getPackageInfo(WHATSAPP_PACKAGE, 0); true }
        catch (e: Exception) { false }

    /**
     * Open WhatsApp to send a message to a contact.
     * Brings WhatsApp to foreground — user can review before sending.
     * For full auto-send, Accessibility Service is required (not implemented in this prototype).
     */
    fun sendWhatsApp(contact: Contact, message: String): SendResult {
        if (!isWhatsAppInstalled()) return SendResult.Error("WhatsApp nu este instalat.")

        val phone = contact.phoneNumbers.firstOrNull()
            ?: return SendResult.Error("Contactul nu are număr de telefon.")

        // Strip non-digits for the wa.me link
        val cleanPhone = phone.replace(Regex("[^\\d+]"), "")

        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(message)}")
                setPackage(WHATSAPP_PACKAGE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            SendResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "WhatsApp send failed", e)
            SendResult.Error("Nu s-a putut deschide WhatsApp: ${e.message}")
        }
    }

    /** Send SMS via system SmsManager. */
    fun sendSms(contact: Contact, message: String): SendResult {
        val phone = contact.phoneNumbers.firstOrNull()
            ?: return SendResult.Error("Contactul nu are număr de telefon.")
        return try {
            @Suppress("DEPRECATION")
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            SendResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "SMS send failed", e)
            SendResult.Error("Eroare la trimiterea SMS: ${e.message}")
        }
    }

    /** Open the default SMS composer with pre-filled recipient and text. */
    fun composeSms(contact: Contact, message: String): SendResult {
        val phone = contact.phoneNumbers.firstOrNull()
            ?: return SendResult.Error("Contactul nu are număr de telefon.")
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phone")
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            SendResult.Success
        } catch (e: Exception) {
            SendResult.Error("Nu s-a putut deschide aplicația de SMS.")
        }
    }
}
