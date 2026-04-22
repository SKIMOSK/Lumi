package com.lumi.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

class LumiTTS(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingLang = "ro"
    private val pendingQueue = mutableListOf<Pair<String, () -> Unit>>()

    // ─── Speed & interrupt tracking ───────────────────────────────────────────

    var speechRate: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            tts?.setSpeechRate(field)
        }

    @Volatile var lastSpokenText: String = ""
    @Volatile private var lastInterruptedPos: Int = 0

    /** True when stop() was called while TTS was actively speaking (user interrupted). */
    @Volatile var wasInterrupted: Boolean = false

    init { tts = TextToSpeech(context, this) }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            applyLanguage(pendingLang)
            tts?.setSpeechRate(speechRate)
            pendingQueue.forEach { (t, cb) -> doSpeak(t, cb) }
            pendingQueue.clear()
        }
    }

    fun setLanguage(lang: String) {
        pendingLang = lang
        if (isReady) applyLanguage(lang)
    }

    private fun applyLanguage(lang: String) {
        val locale = when {
            lang.startsWith("ro") -> Locale("ro", "RO")
            lang.startsWith("en") -> Locale.US
            lang.startsWith("fr") -> Locale.FRANCE
            lang.startsWith("de") -> Locale.GERMANY
            else -> Locale.getDefault()
        }
        tts?.language = locale
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isReady) { pendingQueue.add(text to (onDone ?: {})); return }
        doSpeak(text, onDone ?: {})
    }

    private fun doSpeak(text: String, onDone: () -> Unit) {
        lastSpokenText = text
        lastInterruptedPos = 0
        val id = UUID.randomUUID().toString()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(uid: String?) {}
            override fun onDone(uid: String?) { if (uid == id) onDone() }
            @Deprecated("Deprecated in Java")
            override fun onError(uid: String?) { if (uid == id) onDone() }
            // API 26+ — tracks character position as TTS progresses
            override fun onRangeStart(uid: String?, start: Int, end: Int, frame: Int) {
                if (uid == id) lastInterruptedPos = start
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** Stops TTS and marks [wasInterrupted] = true so the caller can resume later. */
    fun stopWithInterrupt() {
        if (tts?.isSpeaking == true) wasInterrupted = true
        tts?.stop()
    }

    /** Re-speaks from the position where speech was last interrupted. */
    fun resumeFromLastPosition() {
        val text = lastSpokenText
        val pos = lastInterruptedPos.coerceAtMost(text.length)
        if (text.isNotEmpty() && pos < text.length) speak(text.substring(pos))
    }

    /** Suspends until TTS finishes speaking. */
    suspend fun speakAndWait(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        speak(text) { if (cont.isActive) cont.resume(Unit) }
    }

    fun stop() = tts?.stop()

    fun destroy() { tts?.stop(); tts?.shutdown(); tts = null }
}
