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

    // ─── Streaming ────────────────────────────────────────────────────────────

    fun openSpotify(query: String? = null): SendResult {
        val uri = if (query != null) "spotify://search/${Uri.encode(query)}" else "spotify://"
        return openAppByPackage("com.spotify.music", uri, "Spotify nu este instalat.")
    }

    fun openYouTubeMusic(query: String? = null): SendResult {
        val uri: String? = query?.let { "https://music.youtube.com/search?q=${Uri.encode(it)}" }
        return openAppByPackage("com.google.android.apps.youtube.music", uri, "YouTube Music nu este instalat.")
    }

    fun openNetflix(query: String? = null): SendResult {
        val uri = query?.let { "nflx://www.netflix.com/search?q=${Uri.encode(it)}" } ?: "nflx://"
        return openAppByPackage("com.netflix.mediaclient", uri, "Netflix nu este instalat.")
    }

    // ─── Smart home ───────────────────────────────────────────────────────────

    fun openGoogleHome(): SendResult =
        openAppByPackage("com.google.android.apps.chromecast.app", null, "Google Home nu este instalat.")

    // ─── Health ───────────────────────────────────────────────────────────────

    fun openHealthConnect(): SendResult =
        openAppByPackage("com.google.android.apps.healthdata", null, "Health Connect nu este instalat.")

    fun openGoogleFit(): SendResult =
        openAppByPackage("com.google.android.apps.fitness", null, "Google Fit nu este instalat.")

    fun openStrava(): SendResult =
        openAppByPackage("com.strava", null, "Strava nu este instalat.")

    fun openMyFitnessPal(): SendResult =
        openAppByPackage("com.myfitnesspal.android", null, "MyFitnessPal nu este instalat.")

    // ─── Banking ─────────────────────────────────────────────────────────────

    fun openRevolut(): SendResult =
        openAppByPackage("com.revolut.revolut", "revolut://", "Revolut nu este instalat.")

    fun openBTpay(): SendResult =
        openAppByPackage("ro.btrl.mobile", null, "BTpay (Banca Transilvania) nu este instalat.")

    fun openWise(): SendResult =
        openAppByPackage("com.transferwise.android", null, "Wise nu este instalat.")

    fun openPayPal(): SendResult =
        openAppByPackage("com.paypal.android.p2pmobile", "paypal://", "PayPal nu este instalat.")

    // ─── Shopping ─────────────────────────────────────────────────────────────

    fun openAmazonSearch(query: String): SendResult {
        val uri = "amazon://search/query?k=${Uri.encode(query)}"
        return openAppByPackage("com.amazon.mShoppingApp", uri,
            "Amazon nu este instalat.").let { r ->
            if (r is SendResult.Error) {
                // Fallback: web
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.amazon.com/s?k=${Uri.encode(query)}")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    SendResult.DeepLinkOpened
                } catch (e: Exception) { r }
            } else r
        }
    }

    fun openAmazonOrders(): SendResult =
        openAppByPackage("com.amazon.mShoppingApp", "amazon://orders", "Amazon nu este instalat.")

    fun openEbaySearch(query: String): SendResult {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.ebay.com/sch/i.html?_nkw=${Uri.encode(query)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            SendResult.DeepLinkOpened
        } catch (e: Exception) { SendResult.Error("Nu s-a putut deschide eBay: ${e.message}") }
    }

    fun openAliExpressSearch(query: String): SendResult {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.aliexpress.com/wholesale?SearchText=${Uri.encode(query)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            SendResult.DeepLinkOpened
        } catch (e: Exception) { SendResult.Error("Nu s-a putut deschide AliExpress: ${e.message}") }
    }

    // ─── Crypto ───────────────────────────────────────────────────────────────

    fun openCryptoApp(appName: String): SendResult {
        val config: Triple<String, String?, String> = when {
            appName.contains("binance")  -> Triple("com.binance.dev", "bnb://", "Binance")
            appName.contains("coinbase") -> Triple("com.coinbase.android", "coinbase://", "Coinbase")
            appName.contains("metamask") -> Triple("io.metamask", null, "MetaMask")
            appName.contains("trust")    -> Triple("com.wallet.crypto.trustapp", null, "Trust Wallet")
            appName.contains("kraken")   -> Triple("com.kraken.trade", null, "Kraken")
            else                         -> Triple("com.binance.dev", "bnb://", "Binance")
        }
        return openAppByPackage(config.first, config.second, "${config.third} nu este instalat.")
    }

    // ─── News ─────────────────────────────────────────────────────────────────

    fun openGoogleNews(topic: String? = null): SendResult {
        val uri = topic?.let { "googlenews://section/topic/${Uri.encode(it)}" } ?: "googlenews://topstories"
        return openAppByPackage("com.google.android.apps.magazines", uri,
            "Google News nu este instalat.").let { r ->
            if (r is SendResult.Error) {
                try {
                    val webUri = topic?.let { "https://news.google.com/search?q=${Uri.encode(it)}" }
                        ?: "https://news.google.com/"
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUri)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    SendResult.DeepLinkOpened
                } catch (e: Exception) { r }
            } else r
        }
    }

    // ─── Telegram ─────────────────────────────────────────────────────────────

    fun sendTelegram(contact: Contact, message: String): SendResult {
        val phone = contact.phoneNumbers.firstOrNull()
        if (phone != null) {
            val clean = phone.replace(Regex("[^\\d+]"), "")
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("tg://msg?to=$clean&text=${Uri.encode(message)}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                return SendResult.DeepLinkOpened
            } catch (_: Exception) {}
        }
        return openAppByPackage("org.telegram.messenger", null, "Telegram nu este instalat.")
    }

    // ─── Slack ────────────────────────────────────────────────────────────────

    fun openSlack(channel: String?, message: String): SendResult {
        return try {
            context.startActivity(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("com.Slack")
                putExtra(Intent.EXTRA_TEXT, message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            SendResult.DeepLinkOpened
        } catch (_: Exception) {
            openAppByPackage("com.Slack", null, "Slack nu este instalat.")
        }
    }

    // ─── YouTube ──────────────────────────────────────────────────────────────

    fun openYouTubeSearch(query: String): SendResult {
        return try {
            context.startActivity(Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            SendResult.DeepLinkOpened
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                SendResult.DeepLinkOpened
            } catch (e: Exception) {
                SendResult.Error("Nu s-a putut deschide YouTube: ${e.message}")
            }
        }
    }

    fun openYouTubeWatchLater(): SendResult {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/playlist?list=WL")).apply {
                setPackage("com.google.android.youtube")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            SendResult.DeepLinkOpened
        } catch (_: Exception) {
            openAppByPackage("com.google.android.youtube", null, "YouTube nu este instalat.")
        }
    }

    fun openYouTubeLibrary(): SendResult =
        openAppByPackage("com.google.android.youtube", "vnd.youtube://library", "YouTube nu este instalat.")

    // ─── Ride sharing ─────────────────────────────────────────────────────────

    fun openUber(pickup: String?, destination: String): SendResult {
        val pickupParam: String = if (!pickup.isNullOrBlank() &&
            !pickup.lowercase().contains("here") &&
            !pickup.lowercase().contains("current"))
            "&pickup[formatted_address]=${Uri.encode(pickup)}"
        else "&pickup=my_location"
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("uber://?action=setPickup$pickupParam&dropoff[formatted_address]=${Uri.encode(destination)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            SendResult.DeepLinkOpened
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://m.uber.com/ul/?action=setPickup$pickupParam&dropoff[formatted_address]=${Uri.encode(destination)}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                SendResult.DeepLinkOpened
            } catch (e: Exception) { SendResult.Error("Uber nu este instalat.") }
        }
    }

    fun openLyft(pickup: String?, destination: String, rideType: String?): SendResult {
        val rideId = when {
            rideType?.lowercase()?.contains("xl") == true  -> "lyft_plus"
            rideType?.lowercase()?.contains("lux") == true -> "lyft_premier"
            else -> "lyft"
        }
        val pickupParam: String = if (!pickup.isNullOrBlank() &&
            !pickup.lowercase().contains("here") &&
            !pickup.lowercase().contains("current"))
            "&pickup[address]=${Uri.encode(pickup)}"
        else ""
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("lyft://ridetype?id=$rideId$pickupParam&destination[address]=${Uri.encode(destination)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            SendResult.DeepLinkOpened
        } catch (_: Exception) { SendResult.Error("Lyft nu este instalat.") }
    }

    // ─── Food delivery ────────────────────────────────────────────────────────

    fun openFoodDelivery(app: String, foodQuery: String, restaurant: String?): SendResult {
        val searchQuery = restaurant ?: foodQuery
        return when {
            app.contains("doordash") ->
                openAppByPackage("com.dd.consumer", null, "DoorDash nu este instalat.")
            app.contains("deliveroo") ->
                openAppByPackage("com.deliveroo.orderapp", null, "Deliveroo nu este instalat.")
            else -> {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.ubereats.com/search?q=${Uri.encode(searchQuery)}")).apply {
                        setPackage("com.ubercab.eats")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    SendResult.DeepLinkOpened
                } catch (_: Exception) {
                    openAppByPackage("com.ubercab.eats", null, "Uber Eats nu este instalat.")
                }
            }
        }
    }

    // ─── Image sharing ───────────────────────────────────────────────────────

    fun sendWhatsAppImage(contact: Contact, imageUri: android.net.Uri): SendResult {
        if (!isWhatsAppInstalled()) return SendResult.Error("WhatsApp nu este instalat.")
        val phone = contact.phoneNumbers.firstOrNull()
            ?: return SendResult.Error("Contactul nu are număr de telefon.")
        val clean = phone.replace(Regex("[^\\d]"), "")
        return try {
            context.startActivity(Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                setPackage(WHATSAPP_PACKAGE)
                putExtra(Intent.EXTRA_STREAM, imageUri)
                putExtra("jid", "$clean@s.whatsapp.net")
                // clipData required on Android 13+ for URI permission grant to work
                clipData = android.content.ClipData.newRawUri("image", imageUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            SendResult.DeepLinkOpened
        } catch (e: Exception) {
            Log.e(TAG, "WhatsApp image share failed", e)
            SendResult.Error("Nu s-a putut trimite imaginea pe WhatsApp: ${e.message}")
        }
    }

    fun sendInstagramImage(imageUri: android.net.Uri): SendResult {
        return try {
            context.startActivity(Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                setPackage("com.instagram.android")
                putExtra(Intent.EXTRA_STREAM, imageUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            SendResult.DeepLinkOpened
        } catch (e: Exception) {
            SendResult.Error("Nu s-a putut trimite imaginea pe Instagram: ${e.message}")
        }
    }

    fun sendTelegramImage(contact: Contact?, imageUri: android.net.Uri): SendResult {
        return try {
            context.startActivity(Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                setPackage("org.telegram.messenger")
                putExtra(Intent.EXTRA_STREAM, imageUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            SendResult.DeepLinkOpened
        } catch (e: Exception) {
            SendResult.Error("Nu s-a putut trimite imaginea pe Telegram: ${e.message}")
        }
    }

    fun shareImageToApp(imageUri: android.net.Uri, pkg: String, appName: String): SendResult {
        return try {
            context.startActivity(Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                setPackage(pkg)
                putExtra(Intent.EXTRA_STREAM, imageUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            SendResult.DeepLinkOpened
        } catch (e: Exception) {
            SendResult.Error("Nu s-a putut trimite imaginea pe $appName: ${e.message}")
        }
    }

    fun shareDocumentToApp(
        fileUri: android.net.Uri,
        pkg: String?,
        appName: String,
        subject: String? = null,
        body: String? = null,
        emailTo: String? = null
    ): SendResult {
        // Best-effort MIME type from extension, falls back to */* so the app can negotiate.
        val mimeType = guessMime(fileUri) ?: "*/*"
        fun build(action: String, applyPkg: Boolean) = Intent(action).apply {
            type = mimeType
            if (applyPkg && pkg != null) setPackage(pkg)
            putExtra(Intent.EXTRA_STREAM, fileUri)
            if (!subject.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
            if (!body.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, body)
            if (!emailTo.isNullOrBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(emailTo))
            clipData = android.content.ClipData.newRawUri("document", fileUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // 1. Try targeted package
        if (pkg != null) {
            try {
                context.startActivity(build(Intent.ACTION_SEND, applyPkg = true))
                return SendResult.DeepLinkOpened
            } catch (_: Exception) { /* fall through */ }
        }
        // 2. Fall back to a chooser so the user always sees something
        return try {
            val chooser = Intent.createChooser(build(Intent.ACTION_SEND, applyPkg = false), "Trimite via")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            SendResult.DeepLinkOpened
        } catch (e: Exception) {
            SendResult.Error("Nu s-a putut trimite fisierul pe $appName: ${e.message}")
        }
    }

    private fun guessMime(uri: android.net.Uri): String? {
        return try {
            context.contentResolver.getType(uri)
                ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    uri.toString().substringAfterLast('.', "").lowercase()
                )
        } catch (_: Exception) { null }
    }

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
