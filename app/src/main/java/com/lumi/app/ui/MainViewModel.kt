package com.lumi.app.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lumi.app.actions.ActionExecutor
import com.lumi.app.ai.ConversationMemory
import com.lumi.app.ai.GeminiClient
import com.lumi.app.ai.Interaction
import com.lumi.app.ai.TaskRouter
import com.lumi.app.bluetooth.LumiBluetoothManager
import com.lumi.app.consent.ConsentManager
import com.lumi.app.consent.ConsentMode
import com.lumi.app.contacts.ContactsHelper
import com.lumi.app.messaging.MessageSender
import com.lumi.app.settings.AppSettings
import com.lumi.app.timer.TimerManager
import com.lumi.app.timer.TimerReceiver
import com.lumi.app.tts.LumiTTS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val TAG = "MainViewModel"
    val settings = AppSettings(app)
    val bluetooth = LumiBluetoothManager(app)

    val tts = LumiTTS(app).also { it.setLanguage(settings.sttLanguage) }
    val timerManager = TimerManager(app)
    private val contactsHelper = ContactsHelper(app)
    private val messageSender = MessageSender(app)
    val consent = ConsentManager(tts, null) // STT set by MainActivity after creation

    private val memory = ConversationMemory(settings.memorySizeHistory)

    // ─── Consent dialog plumbing (ViewModel ↔ MainActivity) ─────────────────
    data class ConsentRequest(val id: Long, val message: String)
    private val _consentRequest = MutableLiveData<ConsentRequest?>()
    val consentRequest: LiveData<ConsentRequest?> = _consentRequest
    private val pendingConsents = mutableMapOf<Long, CompletableDeferred<Boolean>>()

    init {
        consent.onInAppConsent = { message -> awaitInAppConsent(message) }
        setupTimerDoneCallback()
        setupBluetoothCallbacks()
        registerTimerReceiver(app)
        memory.updateMaxSize(settings.memorySizeHistory)
    }

    private suspend fun awaitInAppConsent(message: String): Boolean {
        val id = System.nanoTime()
        val deferred = CompletableDeferred<Boolean>()
        pendingConsents[id] = deferred
        _consentRequest.postValue(ConsentRequest(id, message))
        return deferred.await()
    }

    fun resolveConsent(id: Long, confirmed: Boolean) {
        pendingConsents[id]?.complete(confirmed)
        pendingConsents.remove(id)
        _consentRequest.postValue(null)
    }

    // ─── Router ──────────────────────────────────────────────────────────────

    private fun buildRouter(): TaskRouter {
        val client = GeminiClient(settings.openRouterApiKey, settings.openRouterBaseUrl)
        val exec = if (settings.actionModeEnabled) {
            ActionExecutor(
                getApplication(), timerManager, consent, contactsHelper, messageSender
            ) { if (bluetooth.connectionState == LumiBluetoothManager.ConnectionState.CONNECTED) ConsentMode.VOICE else ConsentMode.IN_APP }
        } else null
        return TaskRouter(client, settings, timerManager, contactsHelper, exec)
    }

    // ─── UI state ────────────────────────────────────────────────────────────

    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _btState = MutableLiveData(LumiBluetoothManager.ConnectionState.DISCONNECTED)
    val btState: LiveData<LumiBluetoothManager.ConnectionState> = _btState

    private val _isProcessing = MutableLiveData(false)
    val isProcessing: LiveData<Boolean> = _isProcessing

    private val _statusText = MutableLiveData("Inactiv")
    val statusText: LiveData<String> = _statusText

    private var latestImageBase64: String? = null
    private var pendingAttachImage: String? = null  // manually attached from gallery/camera
    private var currentJob: Job? = null
    private var wordLimitBonus = 0

    private fun isAskingForMoreDetail(prompt: String): Boolean {
        val p = prompt.lowercase()
        return listOf("explică mai", "mai mult", "mai detaliat", "mai multe detalii",
            "detaliază", "povestește mai", "explain more", "more detail", "elaborate",
            "în detaliu", "cu mai multe", "extinde").any { p.contains(it) }
    }

    // ─── Process prompt ──────────────────────────────────────────────────────

    fun processPrompt(userText: String, useDeviceImage: Boolean = true) {
        if (!settings.hasApiKey()) { addSystem("Configurează cheia API OpenRouter în Setări."); return }
        if (userText.isBlank()) return

        val wantsDetail = isAskingForMoreDetail(userText)
        if (wantsDetail) wordLimitBonus += 50 else wordLimitBonus = 0
        val expertLimit = 95 + wordLimitBonus
        if (expertLimit > 500) {
            addSystem("Maximum words exceeded, I cannot complete your request.")
            wordLimitBonus = 0
            return
        }

        val imageBase64 = pendingAttachImage ?: (if (useDeviceImage) latestImageBase64 else null)
        pendingAttachImage = null

        appendMessage(ChatMessage(text = userText, time = now(), isUser = true, imageBase64 = imageBase64))
        val loading = ChatMessage(text = "…", time = now(), isUser = false, isLoading = true)
        appendMessage(loading)

        _isProcessing.value = true
        _statusText.value = "Procesez…"

        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            try {
                val result = buildRouter().route(
                    userText, imageBase64, memory,
                    fastWordLimit = 45, expertWordLimit = expertLimit, forceExpert = wantsDetail
                )
                val displayText = result.parsed.displayText

                // Append action results to display text if any
                val actionSuffix = if (result.actionResults.isNotEmpty()) {
                    "\n\n" + result.actionResults.joinToString("\n") { r ->
                        if (r.success) "✓ ${r.message}" else "✗ ${r.message}"
                    }
                } else ""

                val lumiMsg = ChatMessage(
                    text = displayText + actionSuffix,
                    time = now(), isUser = false, usedPro = result.usedExpert
                )
                replaceLoading(loading.id, lumiMsg)

                memory.add(Interaction(userText, displayText, imageBase64, result.usedExpert))
                latestImageBase64 = null
                _statusText.postValue(if (result.usedExpert) "Răspuns · Expert" else "Răspuns · Fast")

                // TTS: speak response if Lumi device is connected
                if (bluetooth.connectionState == LumiBluetoothManager.ConnectionState.CONNECTED) {
                    tts.speak(displayText)
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI error", e)
                replaceLoading(loading.id, ChatMessage(text = "Eroare: ${e.message}", time = now(), isUser = false))
                _statusText.postValue("Eroare")
            } finally {
                _isProcessing.postValue(false)
            }
        }
    }

    fun attachImage(base64: String) { pendingAttachImage = base64 }

    fun clearHistory() { memory.clear(); _messages.value = emptyList() }
    fun connectBluetooth() { if (settings.hasBtDevice()) bluetooth.connectToAddress(settings.btDeviceAddress) else bluetooth.startScan() }
    fun disconnectBluetooth() = bluetooth.disconnect()

    fun refreshMemorySize() = memory.updateMaxSize(settings.memorySizeHistory)

    // ─── Bluetooth setup ─────────────────────────────────────────────────────

    private fun setupBluetoothCallbacks() {
        bluetooth.setListener(object : LumiBluetoothManager.Listener {
            override fun onConnectionStateChanged(state: LumiBluetoothManager.ConnectionState) {
                _btState.postValue(state)
                _statusText.postValue(when (state) {
                    LumiBluetoothManager.ConnectionState.SCANNING    -> "Caut dispozitiv Lumi…"
                    LumiBluetoothManager.ConnectionState.CONNECTING  -> "Conectare…"
                    LumiBluetoothManager.ConnectionState.CONNECTED   -> "Conectat la Lumi"
                    LumiBluetoothManager.ConnectionState.DISCONNECTED -> "Deconectat"
                })
            }
            override fun onAudioFrame(pcmData: ByteArray, sequenceNum: Int) {}
            override fun onImageReceived(jpegData: ByteArray) {
                latestImageBase64 = Base64.encodeToString(jpegData, Base64.NO_WRAP)
            }
            override fun onError(message: String) = addSystem("Eroare BT: $message")
        })
    }

    // ─── Timer done callback ─────────────────────────────────────────────────

    private fun setupTimerDoneCallback() {
        timerManager.onTimerDone = { timer ->
            val msg = "Aceasta este amintirea ta pentru ${timer.name}"
            tts.speak(msg)
            // Also surface it in chat
            appendMessage(ChatMessage(text = "⏰ $msg", time = now(), isUser = false))
            // If BT connected, send TTS to device via command
            if (bluetooth.connectionState == LumiBluetoothManager.ConnectionState.CONNECTED) {
                bluetooth.sendCommand(LumiBluetoothManager.CMD_SPEAK)
            }
        }
    }

    private fun registerTimerReceiver(app: Application) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val name = intent?.getStringExtra(TimerReceiver.EXTRA_TIMER_NAME) ?: return
                val timer = timerManager.getAll().firstOrNull { it.name.equals(name, ignoreCase = true) }
                timer?.let { timerManager.onTimerDone?.invoke(it) }
            }
        }
        app.registerReceiver(receiver, IntentFilter(TimerReceiver.ACTION_INTERNAL),
            Context.RECEIVER_NOT_EXPORTED)
    }

    // ─── Message helpers ─────────────────────────────────────────────────────

    private fun appendMessage(msg: ChatMessage) {
        val list = _messages.value?.toMutableList() ?: mutableListOf()
        list.add(msg)
        _messages.postValue(list)
    }

    private fun replaceLoading(id: Long, replacement: ChatMessage) {
        val list = _messages.value?.toMutableList() ?: return
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = replacement else list.add(replacement)
        _messages.postValue(list)
    }

    private fun addSystem(text: String) = appendMessage(ChatMessage(text = text, time = now(), isUser = false))
    private fun now() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    override fun onCleared() {
        super.onCleared()
        bluetooth.disconnect()
        tts.destroy()
    }
}
