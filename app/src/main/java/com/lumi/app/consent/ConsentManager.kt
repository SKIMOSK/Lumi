package com.lumi.app.consent

import com.lumi.app.stt.RomanianSTT
import com.lumi.app.tts.LumiTTS
import kotlinx.coroutines.delay

enum class ConsentMode { VOICE, IN_APP }

private val AFFIRMATIONS = setOf(
    "da", "yes", "corect", "correct", "sigur", "sure",
    "trimite", "send", "yeah", "yep", "ok", "confirmat", "merge"
)

class ConsentManager(
    private val tts: LumiTTS,
    stt: RomanianSTT?,
    /** Set by MainActivity to show a dialog; returns true if user confirms. */
    var onInAppConsent: (suspend (String) -> Boolean)? = null
) {
    var stt: RomanianSTT? = stt
    suspend fun request(message: String, mode: ConsentMode): Boolean = when (mode) {
        ConsentMode.VOICE -> voiceConsent(message)
        ConsentMode.IN_APP -> onInAppConsent?.invoke(message) ?: false
    }

    private suspend fun voiceConsent(message: String): Boolean {
        tts.speakAndWait(message)
        delay(400)
        return try {
            val heard = stt?.listenOnce() ?: return false
            AFFIRMATIONS.any { heard.lowercase().contains(it) }
        } catch (e: Exception) { false }
    }
}
