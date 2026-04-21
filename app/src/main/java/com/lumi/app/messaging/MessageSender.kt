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
    /** Message was delivered without opening any app UI. */
    object SentSilently : SendResult()
    /** App was opened with the message pre-filled; accessibility service must tap Send. */
    object DeepLinkOpened : SendResult()
    data class Error(val reason: String) : SendResult()
}

class MessageSender(private val context: Context) {

    companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val TAG = "MessageSender"
    }

    // ─── WhatsApp ─────────────────────────────────────────────────────────────

    fun isWhatsAppInstalled() = try {
        context.packageManager.getPackageInfo(WHATSAPP_PACKAGE, 0); true
    } catch (e: Exception) { false }

    fun sendWhatsApp(contact: Contact, message: String): SendResult {
        if (!isWhatsAppInstalled()) return SendResult.Error("WhatsApp nu este instalat.")
        val notif = LumiNotificationService.findReplyable(WHATSAPP_PACKAGE, contact.name)
        if (notif?.directReply != null) return trySilentReply(notif.directReply!!, message)
        val phone = contact.phoneNumbers.firstOrNull()
            ?: return SendResult.Error("Contactul nu are număr de telefon.")
        val clean = phone.replace(Regex("[^\\d+]"), "")
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://api.whatsapp.com/send?phone=$clean&text=${Uri.encode(message)}")
                setPackage(WHATSAPP_PACKAGE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            SendResult.DeepLinkOpened
        } catch (e: Exception) {
            Log.e(TAG, "WhatsApp deep-link failed", e)
            SendResult.Error("Nu s-a putut deschide WhatsApp: ${e.message}")
        }
    }

    fun trySilentReply(reply: com.lumi.app.notifications.DirectReply, message: String): SendResult {
        return try {
            val intent = Intent()
            android.app.RemoteInput.addResultsToIntent(
                arrayOf(reply.remoteInput), intent,
                Bundle().apply { putCharSequence(reply.resultKey, message) }
            )
            reply.replyPendingIntent.send(context, 0, intent)
            SendResult.SentSilently
        } catch (e: Exception) {
            Log.e(TAG, "Silent reply failed", e)
            SendResult.Error("Eroare la trimitere silentioasa: ${e.message}")
        }
    }

    // ─── SMS ──────────────────────────────────────────────────────────────────

    fun sendSms(contact: Contact, message: String): SendResult {
        val phone = contact.phoneNumbers.firstOrNull()
            ?: return SendResult.Error("Contactul nu are numar.")
        return try {
            @Suppress("DEPRECATION")
            val mgr = SmsManager.getDefault()
            mgr.sendMultipartTextMessage(phone, null, mgr.divideMessage(message), null, null)
            SendResult.SentSilently
        } catch (e: Exception) {
            Log.e(TAG, "SMS failed", e)
            SendResult.Error("Eroare SMS: ${e.message}")
        }
    }

    fun composeSms(contact: Contact, message: String): SendResult {
        val phone = contact.phoneNumbers.firstOrNull()
            ?: return SendResult.Error("Contactul nu are numar.")
        return try {
            context.startActivity(Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phone")
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            SendResult.DeepLinkOpened
        } catch (e: Exception) { SendResult.Error("Nu s-a putut deschide SMS.") }
    }

    // ─── Social media ─────────────────────────────────────────────────────────

    fun openInstagramDM(username: String): SendResult {
        // Try to open Instagram DM inbox via URI scheme
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("instagram://direct/inbox")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            SendResult.DeepLinkOpened
        } catch (e: Exception) {
            openAppByPackage("com.instagram.android", null, "Instagram nu este instalat.")
        }
    }

    fun openSnapchatChat(username: String): SendResult {
        return openAppByPackage("com.snapchat.android", null, "Snapchat nu este instalat.")
    }

    fun openFacebookMessenger(contact: Contact): SendResult {
        // Try deep link with phone number first
        val phone = contact.phoneNumbers.firstOrNull()
        if (phone != null) {
            val clean = phone.replace(Regex("[^\\d]"), "")
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("fb://messaging/$clean")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                return SendResult.DeepLinkOpened
            } catch (e: Exception) { /* fall through */ }
        }
        return openAppByPackage("com.facebook.orca", null, "Facebook Messenger nu este instalat.")
    }

    fun openDiscordDM(username: String): SendResult {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("discord://")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            SendResult.DeepLinkOpened
        } catch (e: Exception) {
            openAppByPackage("com.discord", null, "Discord nu este instalat.")
        }
    }

    // ─── Email ────────────────────────────────────────────────────────────────

    fun composeEmail(to: String, subject: String, body: String): SendResult {
        return try {
            context.startActivity(Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:${Uri.encode(to)}")
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            SendResult.DeepLinkOpened
        } catch (e: Exception) {
            // Fallback: ACTION_SEND to any email app
            try {
                context.startActivity(Intent(Intent.ACTION_SEND).apply {
                    type = "message/rfc822"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                SendResult.DeepLinkOpened
            } catch (e2: Exception) { SendResult.Error("Nu s-a putut deschide aplicatia de email: ${e2.message}") }
        }
    }

    fun openGmail(): SendResult = openAppByPackage("com.google.android.gm", null, "Gmail nu este instalat.")

    // ─── Navigation ───────────────────────────────────────────────────────────

    fun openGoogleMaps(destination: String): SendResult {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("google.navigation:q=${Uri.encode(destination)}&avoid=tf")
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            SendResult.DeepLinkOpened
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://maps.google.com/maps?daddr=${Uri.encode(destination)}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                SendResult.DeepLinkOpened
            } catch (e2: Exception) { SendResult.Error("Nu s-a putut deschide Google Maps: ${e2.message}") }
        }
    }

    fun openWaze(destination: String): SendResult {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("waze://?q=${Uri.encode(destination)}&navigate=yes")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            SendResult.DeepLinkOpened
        } catch (e: Exception) { SendResult.Error("Waze nu este instalat.") }
    }

    // ─── VPN ──────────────────────────────────────────────────────────────────

    fun openVpnApp(pkg: String): SendResult =
        openAppByPackage(pkg, null, "Aplicatia VPN nu este instalata.")

    // ─── Generic helper ───────────────────────────────────────────────────────

    fun openAppByPackage(pkg: String, fallbackUri: String?, errorMsg: String): SendResult {
        if (fallbackUri != null) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUri)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                return SendResult.DeepLinkOpened
            } catch (e: Exception) { /* try launch intent */ }
        }
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return SendResult.Error(errorMsg)
            context.startActivity(intent.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
            SendResult.DeepLinkOpened
        } catch (e: Exception) { SendResult.Error("$errorMsg: ${e.message}") }
    }
}
