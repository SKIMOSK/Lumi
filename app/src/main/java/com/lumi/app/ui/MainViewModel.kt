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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
import com.lumi.app.notes.NotesHelper
import com.lumi.app.settings.AppSettings
import com.lumi.app.system.SystemSettingsHelper
import com.lumi.app.timer.TimerManager
import com.lumi.app.timer.TimerReceiver
import com.lumi.app.tts.LumiTTS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val TAG = "MainViewModel"
    val settings    = AppSettings(app)
    val bluetooth   = LumiBluetoothManager(app)
    val tts         = LumiTTS(app).also { it.setLanguage(settings.sttLanguage) }
    val timerManager = TimerManager(app)
    private val contactsHelper  = ContactsHelper(app)
    private val messageSender   = MessageSender(app)
    private val notesHelper     = NotesHelper(app)
    private val sysSettings     = SystemSettingsHelper(app)
    val consent = ConsentManager(tts, null)

    private val memory = ConversationMemory(settings.memorySizeHistory)

    // ─── Consent dialog (ViewModel ↔ MainActivity) ───────────────────────────
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
        loadChatHistory()
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
        val mode: () -> ConsentMode = {
            when {
                settings.autonomousMode -> ConsentMode.AUTONOMOUS
                bluetooth.connectionState == LumiBluetoothManager.ConnectionState.CONNECTED -> ConsentMode.VOICE
                else -> ConsentMode.IN_APP
            }
        }
        val exec = if (settings.actionModeEnabled) {
            ActionExecutor(getApplication(), timerManager, consent, contactsHelper,
                messageSender, notesHelper, sysSettings, mode)
        } else null
        return TaskRouter(client, settings, timerManager, contactsHelper, notesHelper, sysSettings, exec)
    }

    // ─── UI state ────────────────────────────────────────────────────────────

    private val messageList = mutableListOf<ChatMessage>()
    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _btState = MutableLiveData(LumiBluetoothManager.ConnectionState.DISCONNECTED)
    val btState: LiveData<LumiBluetoothManager.ConnectionState> = _btState

    private val _isProcessing = MutableLiveData(false)
    val isProcessing: LiveData<Boolean> = _isProcessing

    private val _statusText = MutableLiveData("Inactiv")
    val statusText: LiveData<String> = _statusText

    private var latestImageBase64: String? = null
    private var pendingAttachImage: String? = null
    private var currentJob: Job? = null
    private var wordLimitBonus = 0

    // ─── Message persistence ─────────────────────────────────────────────────

    private val historyFile = File(getApplication<Application>().filesDir, "chat_history.json")
    private val gson = Gson()

    private fun loadChatHistory() {
        try {
            if (!historyFile.exists()) return
            val type = object : TypeToken<List<ChatMessage>>() {}.type
            val loaded: List<ChatMessage>? = gson.fromJson(historyFile.readText(), type)
            loaded?.let {
                messageList.addAll(it.filter { m -> !m.isLoading })
                _messages.value = messageList.toList()
            }
        } catch (e: Exception) { historyFile.delete() }
    }

    private fun saveChatHistory() {
        try {
            val toSave = messageList.filter { !it.isLoading }.takeLast(100)
            historyFile.writeText(gson.toJson(toSave))
        } catch (e: Exception) { Log.w(TAG, "Failed to save history", e) }
    }

    // ─── Process prompt ──────────────────────────────────────────────────────

    private fun isAskingForMoreDetail(prompt: String): Boolean {
        val p = prompt.lowercase()
        return listOf("explică mai", "mai mult", "mai detaliat", "mai multe detalii",
            "detaliază", "povestește mai", "explain more", "more detail", "elaborate",
            "în detaliu", "cu mai multe", "extinde").any { p.contains(it) }
    }

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

        val userMsg = ChatMessage(text = userText, time = now(), isUser = true, imageBase64 = imageBase64)
        val loading = ChatMessage(text = "…", time = now(), isUser = false, isLoading = true)
        messageList.add(userMsg)
        messageList.add(loading)
        _messages.value = messageList.toList()

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
                val actionSuffix = if (result.actionResults.isNotEmpty()) {
                    "\n\n" + result.actionResults.joinToString("\n") { r ->
                        if (r.success) "OK: ${r.message}" else "Eroare: ${r.message}"
                    }
                } else ""

                val lumiMsg = ChatMessage(text = displayText + actionSuffix,
                    time = now(), isUser = false, usedPro = result.usedExpert)

                withContext(Dispatchers.Main) {
                    val idx = messageList.indexOfFirst { it.id == loading.id }
                    if (idx >= 0) messageList[idx] = lumiMsg else messageList.add(lumiMsg)
                    _messages.value = messageList.toList()
                }
                saveChatHistory()

                val aiAskedQuestion = displayText.trimEnd().endsWith("?")
                if (aiAskedQuestion) {
                    memory.addPending(Interaction(userText, displayText, imageBase64, result.usedExpert))
                } else {
                    memory.clearPending()
                    memory.add(Interaction(userText, displayText, imageBase64, result.usedExpert))
                }
                latestImageBase64 = null
                _statusText.postValue(if (result.usedExpert) "Raspuns Expert" else "Raspuns Fast")

                if (bluetooth.connectionState == LumiBluetoothManager.ConnectionState.CONNECTED) {
                    tts.speak(displayText)
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI error", e)
                withContext(Dispatchers.Main) {
                    val idx = messageList.indexOfFirst { it.id == loading.id }
                    val errMsg = ChatMessage(text = "Eroare: ${e.message}", time = now(), isUser = false)
                    if (idx >= 0) messageList[idx] = errMsg else messageList.add(errMsg)
                    _messages.value = messageList.toList()
                }
                _statusText.postValue("Eroare")
            } finally {
                _isProcessing.postValue(false)
            }
        }
    }

    fun attachImage(base64: String) { pendingAttachImage = base64 }

    fun clearHistory() {
        memory.clear(); wordLimitBonus = 0
        messageList.clear()
        _messages.value = emptyList()
        historyFile.delete()
    }

    fun connectBluetooth() {
        if (settings.hasBtDevice()) bluetooth.connectToAddress(settings.btDeviceAddress)
        else bluetooth.startScan()
    }
    fun disconnectBluetooth() = bluetooth.disconnect()
    fun refreshMemorySize() = memory.updateMaxSize(settings.memorySizeHistory)

    // ─── Bluetooth ───────────────────────────────────────────────────────────

    private fun setupBluetoothCallbacks() {
        bluetooth.setListener(object : LumiBluetoothManager.Listener {
            override fun onConnectionStateChanged(state: LumiBluetoothManager.ConnectionState) {
                _btState.postValue(state)
                _statusText.postValue(when (state) {
                    LumiBluetoothManager.ConnectionState.SCANNING    -> "Caut dispozitiv Lumi..."
                    LumiBluetoothManager.ConnectionState.CONNECTING  -> "Conectare..."
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

    // ─── Timer callback ───────────────────────────────────────────────────────

    private fun setupTimerDoneCallback() {
        timerManager.onTimerDone = { timer ->
            val msg = "Aceasta este amintirea ta pentru ${timer.name}"
            tts.speak(msg)
            addSystem("⏰ $msg")
            if (bluetooth.connectionState == LumiBluetoothManager.ConnectionState.CONNECTED) {
                bluetooth.sendCommand(LumiBluetoothManager.CMD_SPEAK)
            }
        }
    }

    private fun registerTimerReceiver(app: Application) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val name = intent?.getStringExtra(TimerReceiver.EXTRA_TIMER_NAME) ?: return
                timerManager.getAll().firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?.let { timerManager.onTimerDone?.invoke(it) }
            }
        }
        val filter = IntentFilter(TimerReceiver.ACTION_INTERNAL)
        // 3-arg registerReceiver(flags) only exists on API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
    }

    // ─── Message helpers ─────────────────────────────────────────────────────

    private fun addSystem(text: String) {
        val msg = ChatMessage(text = text, time = now(), isUser = false)
        messageList.add(msg)
        _messages.postValue(messageList.toList())
        saveChatHistory()
    }

    private fun now() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    override fun onCleared() {
        super.onCleared()
        bluetooth.disconnect()
        tts.destroy()
    }
}
