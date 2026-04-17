package com.lumi.app.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RomanianSTT(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var language: String = "ro-RO"

    fun setLanguage(lang: String) { language = lang }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERRED, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
    }

    /** Listens once and returns the best transcript. Must be called on main thread. */
    suspend fun listenOnce(
        onPartialResult: ((String) -> Unit)? = null,
        onReadyForSpeech: (() -> Unit)? = null
    ): String = suspendCancellableCoroutine { cont ->

        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { onReadyForSpeech?.invoke() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                val msg = sttErrorMessage(error)
                if (cont.isActive) cont.resumeWithException(Exception(msg))
                destroy()
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { onPartialResult?.invoke(it) }
            }
            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = texts?.firstOrNull() ?: ""
                if (cont.isActive) cont.resume(best)
                destroy()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer?.startListening(buildIntent())

        cont.invokeOnCancellation { destroy() }
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun sttErrorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Eroare audio"
        SpeechRecognizer.ERROR_CLIENT -> "Eroare client STT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permisiuni insuficiente pentru microfon"
        SpeechRecognizer.ERROR_NETWORK -> "Eroare de rețea STT"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Timeout rețea STT"
        SpeechRecognizer.ERROR_NO_MATCH -> "Nu s-a recunoscut nicio vorbire"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recunoscătorul STT este ocupat"
        SpeechRecognizer.ERROR_SERVER -> "Eroare server STT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nicio vorbire detectată"
        else -> "Eroare STT necunoscută ($error)"
    }
}
