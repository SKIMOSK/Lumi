package com.lumi.app.ui

import android.app.Application
import android.net.Uri
import android.os.Build
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
import com.lumi.app.bluetooth.BluetoothDeviceManager
import com.lumi.app.bluetooth.LumiBluetoothManager
import com.lumi.app.calendar.CalendarHelper
import com.lumi.app.consent.ConsentManager
import com.lumi.app.consent.ConsentMode
import com.lumi.app.contacts.ContactsHelper
import com.lumi.app.files.FileHelper
import com.lumi.app.gallery.GallerySearchHelper
import com.lumi.app.location.GeofenceHelper
import com.lumi.app.location.LocationHelper
import com.lumi.app.location.PlaceSearchHelper
import com.lumi.app.location.UserLocations
import com.lumi.app.messaging.MessageSender
import com.lumi.app.notes.NotesHelper
import com.lumi.app.notes.UserMemory
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
    val tts         = LumiTTS(app).also {
        it.setLanguage(settings.sttLanguage)
        it.speechRate = settings.ttsSpeed
    }
    val timerManager = TimerManager(app)
    private val contactsHelper    = ContactsHelper(app)
    private val messageSender     = MessageSender(app)
    private val notesHelper       = NotesHelper(app)
    private val sysSettings       = SystemSettingsHelper(app)
    val userMemory                = UserMemory(app)
    private val btDeviceManager   = BluetoothDeviceManager(app)
    private val gallerySearchHelper = GallerySearchHelper(app)
    private val documentHelper    = com.lumi.app.system.DocumentHelper(app)
    private val locationHelper    = LocationHelper(app)
    private val placeSearchHelper = PlaceSearchHelper()
    private val userLocations     = UserLocations(app)
    private val geofenceHelper    = GeofenceHelper(app)
    private val fileHelper = FileHelper(app)
    val consent = ConsentManager(tts, null)

    private val memory = ConversationMemory(settings.memorySizeHistory)

    // ─── Consent dialog (ViewModel ↔ MainActivity) ───────────────────────────
    data class ConsentRequest(val id: Long, val message: String)
    private val _consentRequest = MutableLiveData<ConsentRequest?>()
    val consentRequest: LiveData<ConsentRequest?> = _consentRequest
    private val pendingConsents = mutableMapOf<Long, CompletableDeferred<Boolean>>()

    // ─── UI state ────────────────────────────────────────────────────────────

    private val messageList = mutableListOf<ChatMessage>()
    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _btState = MutableLiveData(LumiBluetoothManager.ConnectionState.DISCONNECTED)
    val btState: LiveData<LumiBluetoothManager.ConnectionState> = _btState

    private val _deviceColorTheme = MutableLiveData(settings.getDeviceTheme(settings.btDeviceAddress))
    val deviceColorTheme: LiveData<String> = _deviceColorTheme

    private val _isProcessing = MutableLiveData(false)
    val isProcessing: LiveData<Boolean> = _isProcessing

    private val _statusText = MutableLiveData("Inactiv")
    val statusText: LiveData<String> = _statusText

    // Translation mode
    private val _translationActive = MutableLiveData(false)
    val translationActive: LiveData<Boolean> = _translationActive
    private var translateFrom = "auto"
    private var translateTo = settings.sttLanguage

    private var latestImageBase64: String? = null
    private var pendingAttachImage: String? = null
    private var pendingAttachFile: Uri? = null
    private var pendingAttachFileName: String = ""
    private var currentJob: Job? = null
    private var wordLimitBonus = 0

    // ─── Message persistence ─────────────────────────────────────────────────

    private val historyFile = File(getApplication<Application>().filesDir, "chat_history.json")
    private val gson = Gson()

    // ─── Init ────────────────────────────────────────────────────────────────

    init {
        consent.onInAppConsent = { message -> awaitInAppConsent(message) }
        setupTimerDoneCallback()
        setupBluetoothCallbacks()
        registerTimerReceiver(app)
        registerFingerprintSetupReceiver(app)
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
        val exec: ActionExecutor? = if (settings.actionModeEnabled) {
            ActionExecutor(
                getApplication<Application>(), timerManager, consent, contactsHelper,
                messageSender, notesHelper, sysSettings, mode,
                userMemory, btDeviceManager, settings.btDeviceAddress,
                tts, settings, gallerySearchHelper, documentHelper,
                locationHelper, userLocations, geofenceHelper,
                onStartTranslation = { from, to -> startTranslationMode(from, to) },
                onStopTranslation = { stopTranslationMode() }
            )
        } else null
        val calendarHelper = CalendarHelper(getApplication<Application>())
        return TaskRouter(
            client, settings, timerManager, contactsHelper, notesHelper,
            sysSettings, calendarHelper, exec, userMemory, btDeviceManager,
            gallerySearchHelper, documentHelper, locationHelper, placeSearchHelper
        )
    }

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

    private fun isTtsSpeedCommand(text: String): Boolean {
        val t = text.lowercase().trim()
        return listOf(
            "mai repede", "mai incet", "mai rapid", "mai lent",
            "faster", "slower", "speak faster", "speak slower",
            "talk faster", "talk slower", "vorbeste mai repede", "vorbeste mai incet"
        ).any { t.contains(it) }
    }

    private fun isFasterCommand(text: String): Boolean {
        val t = text.lowercase()
        return listOf("mai repede", "mai rapid", "faster", "repede").any { t.contains(it) }
    }

    fun processPrompt(userText: String, useDeviceImage: Boolean = true) {
        if (!settings.hasApiKey()) { addSystem("Configurează cheia API OpenRouter în Setări."); return }
        if (userText.isBlank()) return

        // TTS speed interrupt: user stopped speech and asks to change speed
        if (tts.wasInterrupted && isTtsSpeedCommand(userText)) {
            val faster = isFasterCommand(userText)
            val newRate = (settings.ttsSpeed * (if (faster) 1.25f else 0.8f)).coerceIn(0.5f, 2.0f)
            settings.ttsSpeed = newRate
            tts.speechRate = newRate
            tts.wasInterrupted = false
            tts.resumeFromLastPosition()
            return
        }
        tts.wasInterrupted = false

        val wantsDetail = isAskingForMoreDetail(userText)
        if (wantsDetail) wordLimitBonus += 50 else wordLimitBonus = 0
        val expertLimit = 95 + wordLimitBonus
        if (expertLimit > 500) {
            addSystem("Maximum words exceeded, I cannot complete your request.")
            wordLimitBonus = 0
            return
        }

        var imageBase64 = pendingAttachImage ?: (if (useDeviceImage) latestImageBase64 else null)
        pendingAttachImage = null
        // Clear immediately — a bad BLE frame must not poison the next request
        latestImageBase64 = null

        val attachedFileUri = pendingAttachFile
        val attachedFileName = pendingAttachFileName
        pendingAttachFile = null
        pendingAttachFileName = ""

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
                // Resolve file attachment into either image base64 or text context
                var fileContext: String? = null
                if (attachedFileUri != null) {
                    val mime = fileHelper.getMimeType(attachedFileUri)
                    when {
                        fileHelper.isSupportedImageMime(mime) -> {
                            imageBase64 = fileHelper.readAsBase64(attachedFileUri)
                        }
                        fileHelper.isTextMime(mime) -> {
                            val content = fileHelper.readText(attachedFileUri)
                            if (content != null) {
                                fileContext = "=== Fisier atasat: $attachedFileName ===\n$content\n=== Sfarsit fisier ==="
                            }
                        }
                        else -> {
                            fileContext = "[Fisier atasat: $attachedFileName (tip: $mime)]"
                        }
                    }
                }

                // Streaming: replace the loading placeholder with a non-loading bubble
                // and append visible deltas as they arrive.
                val streamingBuf = StringBuilder()
                fun pushStreamUpdate() {
                    val snapshot = streamingBuf.toString().ifBlank { "…" }
                    viewModelScope.launch(Dispatchers.Main) {
                        val idx = messageList.indexOfFirst { it.id == loading.id }
                        if (idx >= 0) {
                            messageList[idx] = ChatMessage(
                                text = snapshot, time = loading.time,
                                isUser = false, isLoading = snapshot == "…", id = loading.id
                            )
                            _messages.value = messageList.toList()
                        }
                    }
                }
                val streamCallback: ((String) -> Unit)? = if (settings.streamingEnabled) {
                    { delta -> streamingBuf.append(delta); pushStreamUpdate() }
                } else null
                val streamReset: (() -> Unit)? = if (settings.streamingEnabled) {
                    { streamingBuf.clear(); pushStreamUpdate() }
                } else null

                val result = buildRouter().route(
                    userText, imageBase64, memory,
                    fastWordLimit = 45, expertWordLimit = expertLimit, forceExpert = wantsDetail,
                    fileContext = fileContext, fileUri = attachedFileUri,
                    onVisibleChunk = streamCallback,
                    onStreamReset = streamReset
                )
                val displayText = result.parsed.displayText
                val actionSuffix = buildString {
                    if (result.actionResults.isNotEmpty()) {
                        append("\n\n")
                        append(result.actionResults.joinToString("\n") { r ->
                            if (r.success) "OK: ${r.message}" else "Eroare: ${r.message}"
                        })
                    }
                    val errors = result.validationIssues.filter {
                        it.severity == com.lumi.app.actions.ActionValidator.Severity.ERROR
                    }
                    if (errors.isNotEmpty()) {
                        append("\n\n")
                        append(errors.joinToString("\n") { i ->
                            "Validare: actiunea ${i.action.type} respinsa — ${i.reason}"
                        })
                    }
                }

                val lumiMsg = ChatMessage(text = displayText + actionSuffix,
                    time = now(), isUser = false, usedPro = result.usedExpert,
                    galleryImageIds = result.galleryImageIds)

                withContext(Dispatchers.Main) {
                    val idx = messageList.indexOfFirst { it.id == loading.id }
                    if (idx >= 0) messageList[idx] = lumiMsg else messageList.add(lumiMsg)
                    _messages.value = messageList.toList()
                }
                saveChatHistory()

                val aiAskedQuestion = displayText.trimEnd().endsWith("?")
                // Feed the AI back what actually happened so it can self-correct on the next turn:
                //   - Successful results (gallery IDs, file names): so it can say "send it"
                //   - Failed action reasons: so it knows not to retry the same broken combo
                //   - Validation errors: so it learns the schema (e.g. missing required field)
                val successLines = result.actionResults
                    .filter { it.success }
                    .joinToString("\n") { it.message }
                val failureLines = result.actionResults
                    .filter { !it.success }
                    .joinToString("\n") { "FAILED ${it.action.type}: ${it.message}" }
                val validationErrors = result.validationIssues
                    .filter { it.severity == com.lumi.app.actions.ActionValidator.Severity.ERROR }
                    .joinToString("\n") { "INVALID ${it.action.type}: ${it.reason}" }
                val feedback = listOf(successLines, failureLines, validationErrors)
                    .filter { it.isNotBlank() }.joinToString("\n")
                val memorizedText = if (feedback.isBlank()) displayText
                                    else "$displayText\n\n[Rezultate:]\n$feedback"
                if (aiAskedQuestion) {
                    memory.addPending(Interaction(userText, memorizedText, imageBase64, result.usedExpert))
                } else {
                    memory.clearPending()
                    memory.add(Interaction(userText, memorizedText, imageBase64, result.usedExpert))
                }
                _statusText.postValue(if (result.usedExpert) "Raspuns Expert" else "Raspuns Fast")

                if (bluetooth.connectionState == LumiBluetoothManager.ConnectionState.CONNECTED) {
                    tts.speak(displayText)
                    // Also speak on the Lumi hardware device
                    withContext(Dispatchers.IO) { bluetooth.sendTtsText(displayText) }
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

    fun attachImage(base64: String) { pendingAttachImage = base64; pendingAttachFile = null }

    fun attachFile(uri: Uri, name: String) {
        pendingAttachFile = uri
        pendingAttachFileName = name
        pendingAttachImage = null
        latestImageBase64 = null
    }

    fun clearPendingFile() { pendingAttachFile = null; pendingAttachFileName = "" }

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
                if (state == LumiBluetoothManager.ConnectionState.CONNECTED) {
                    // Sync fingerprint setting to device on connection
                    val fpCmd = if (settings.fingerprintEnabled)
                        LumiBluetoothManager.CMD_FINGERPRINT_ON
                    else
                        LumiBluetoothManager.CMD_FINGERPRINT_OFF
                    bluetooth.sendCommand(fpCmd)
                }
            }
            override fun onAudioFrame(pcmData: ByteArray, sequenceNum: Int) {}
            override fun onImageReceived(jpegData: ByteArray) {
                latestImageBase64 = Base64.encodeToString(jpegData, Base64.NO_WRAP)
            }
            override fun onError(message: String) = addSystem("Eroare BT: $message")
            override fun onSpeechText(text: String) {
                viewModelScope.launch(Dispatchers.Main) {
                    processPrompt(text, useDeviceImage = true)
                }
            }
            override fun onDeviceInfo(deviceId: String, colorTheme: String, fingerprintEnabled: Boolean) {
                settings.setDeviceTheme(settings.btDeviceAddress, colorTheme)
                _deviceColorTheme.postValue(colorTheme)
                // Notify SettingsActivity (if open) so it refreshes its theme label instantly
                getApplication<Application>().sendBroadcast(
                    Intent("com.lumi.app.DEVICE_INFO_UPDATED")
                )
            }
        })
    }

    // ── Fingerprint setup broadcast (triggered by SettingsActivity button) ────
    fun sendFingerprintSetupCommand() {
        if (bluetooth.connectionState == LumiBluetoothManager.ConnectionState.CONNECTED) {
            bluetooth.sendCommand(LumiBluetoothManager.CMD_SETUP_FP)
        } else {
            addSystem("Dispozitivul nu este conectat.")
        }
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

    private fun registerFingerprintSetupReceiver(app: Application) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                sendFingerprintSetupCommand()
            }
        }
        val filter = IntentFilter("com.lumi.app.SETUP_FINGERPRINT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
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

    // ─── Cancel / Stop ────────────────────────────────────────────────────────

    fun cancelCurrentTask() {
        currentJob?.cancel()
        currentJob = null
        tts.stop()
        if (_translationActive.value == true) stopTranslationMode()
        // Remove dangling loading bubble
        val idx = messageList.indexOfFirst { it.isLoading }
        if (idx >= 0) {
            messageList[idx] = ChatMessage(text = "Anulat.", time = now(), isUser = false)
            _messages.postValue(messageList.toList())
        }
        _isProcessing.postValue(false)
        _statusText.postValue("Anulat")
    }

    // ─── Translation mode ─────────────────────────────────────────────────────

    fun startTranslationMode(from: String = "auto", to: String = settings.sttLanguage) {
        translateFrom = from
        translateTo = to
        _translationActive.postValue(true)
        _statusText.postValue("Mod traducere activ")
        addSystem("Mod traducere pornit. Vorbeste — traduc automat. Apasa Stop pentru a opri.")
    }

    fun stopTranslationMode() {
        _translationActive.postValue(false)
        translateFrom = "auto"
        translateTo = settings.sttLanguage
        _statusText.postValue("Inactiv")
        addSystem("Mod traducere oprit.")
    }

    /**
     * Called from MainActivity when STT fires during translation mode.
     * Sends the recognized text to the AI for translation only (no action routing).
     */
    fun translateAndSpeak(inputText: String) {
        if (!settings.hasApiKey()) return
        if (inputText.isBlank()) return

        val loading = ChatMessage(text = "…", time = now(), isUser = false, isLoading = true)
        messageList.add(ChatMessage(text = inputText, time = now(), isUser = true))
        messageList.add(loading)
        _messages.postValue(messageList.toList())
        _isProcessing.postValue(true)

        currentJob = viewModelScope.launch {
            try {
                val client = GeminiClient(settings.openRouterApiKey, settings.openRouterBaseUrl)
                val fromLabel = if (translateFrom == "auto") "limba detectata automat" else translateFrom
                val sysPrompt = "Esti un traducator profesionist. Traduce urmatorul text din $fromLabel in $translateTo. Returneaza NUMAI traducerea, fara explicatii, fara ghilimele, fara text suplimentar."
                val resp = client.generate(
                    prompt = inputText,
                    model = settings.fastModel,
                    systemInstruction = sysPrompt,
                    temperature = 0.2
                )
                val translation = resp.text.trim()
                val lumiMsg = ChatMessage(text = translation, time = now(), isUser = false)
                withContext(Dispatchers.Main) {
                    val idx = messageList.indexOfFirst { it.id == loading.id }
                    if (idx >= 0) messageList[idx] = lumiMsg else messageList.add(lumiMsg)
                    _messages.value = messageList.toList()
                }
                tts.speak(translation)
                if (bluetooth.connectionState == LumiBluetoothManager.ConnectionState.CONNECTED) {
                    withContext(Dispatchers.IO) { bluetooth.sendTtsText(translation) }
                }
            } catch (e: Exception) {
                val errMsg = ChatMessage(text = "Eroare traducere: ${e.message}", time = now(), isUser = false)
                withContext(Dispatchers.Main) {
                    val idx = messageList.indexOfFirst { it.id == loading.id }
                    if (idx >= 0) messageList[idx] = errMsg else messageList.add(errMsg)
                    _messages.value = messageList.toList()
                }
            } finally {
                _isProcessing.postValue(false)
            }
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
