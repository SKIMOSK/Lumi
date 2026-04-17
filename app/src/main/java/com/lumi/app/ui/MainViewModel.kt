package com.lumi.app.ui

import android.app.Application
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lumi.app.ai.ConversationMemory
import com.lumi.app.ai.GeminiClient
import com.lumi.app.ai.Interaction
import com.lumi.app.ai.TaskRouter
import com.lumi.app.bluetooth.LumiBluetoothManager
import com.lumi.app.notifications.LumiNotificationService
import com.lumi.app.settings.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val TAG = "MainViewModel"
    val settings = AppSettings(app)
    val bluetooth = LumiBluetoothManager(app)

    private val gemini get() = GeminiClient(settings.geminiApiKey)
    private val memory = ConversationMemory(maxSize = 5)
    private val router get() = TaskRouter(gemini, settings)

    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _btState = MutableLiveData(LumiBluetoothManager.ConnectionState.DISCONNECTED)
    val btState: LiveData<LumiBluetoothManager.ConnectionState> = _btState

    private val _isProcessing = MutableLiveData(false)
    val isProcessing: LiveData<Boolean> = _isProcessing

    private val _statusText = MutableLiveData("Inactiv")
    val statusText: LiveData<String> = _statusText

    // Latest image frame from Lumi device (base64 JPEG)
    private var latestImageBase64: String? = null

    private var currentJob: Job? = null

    init {
        setupBluetooth()
    }

    private fun setupBluetooth() {
        bluetooth.setListener(object : LumiBluetoothManager.Listener {
            override fun onConnectionStateChanged(state: LumiBluetoothManager.ConnectionState) {
                _btState.postValue(state)
                _statusText.postValue(when (state) {
                    LumiBluetoothManager.ConnectionState.SCANNING -> "Caut dispozitiv Lumi…"
                    LumiBluetoothManager.ConnectionState.CONNECTING -> "Conectare…"
                    LumiBluetoothManager.ConnectionState.CONNECTED -> "Conectat la Lumi"
                    LumiBluetoothManager.ConnectionState.DISCONNECTED -> "Deconectat"
                })
            }

            override fun onAudioFrame(pcmData: ByteArray, sequenceNum: Int) {
                // PCM audio from device mic — accumulated and fed to STT in MainActivity
                // Signal is forwarded via AudioStreamBuffer (managed by MainActivity)
            }

            override fun onImageReceived(jpegData: ByteArray) {
                latestImageBase64 = Base64.encodeToString(jpegData, Base64.NO_WRAP)
                Log.d(TAG, "Image received: ${jpegData.size} bytes")
            }

            override fun onError(message: String) {
                addSystemMessage("Eroare Bluetooth: $message")
            }
        })
    }

    fun connectBluetooth() {
        if (settings.hasBtDevice()) {
            bluetooth.connectToAddress(settings.btDeviceAddress)
        } else {
            bluetooth.startScan()
        }
    }

    fun disconnectBluetooth() = bluetooth.disconnect()

    /** Process a voice/text prompt from user (with optional image from device). */
    fun processPrompt(userText: String, useCurrentImage: Boolean = true) {
        if (!settings.hasApiKey()) {
            addSystemMessage("⚠ Configurează cheia API Gemini în Setări.")
            return
        }
        if (userText.isBlank()) return

        val imageBase64 = if (useCurrentImage) latestImageBase64 else null
        val notifContext = LumiNotificationService.formatSummary(15)

        val userMsg = ChatMessage(
            text = userText,
            time = now(),
            isUser = true,
            imageBase64 = imageBase64
        )
        appendMessage(userMsg)

        val loadingMsg = ChatMessage(
            text = "…",
            time = now(),
            isUser = false,
            isLoading = true
        )
        appendMessage(loadingMsg)

        _isProcessing.value = true
        _statusText.value = "Procesez cu Gemini…"

        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            try {
                val result = router.route(
                    userPrompt = userText,
                    imageBase64 = imageBase64,
                    memory = memory,
                    notificationContext = notifContext.takeIf { it.isNotBlank() }
                )

                val modelLabel = if (result.usedPro) "Pro" else "Flash"
                _statusText.postValue("Răspuns de la Gemini $modelLabel")

                val lumiMsg = ChatMessage(
                    text = result.response.text,
                    time = now(),
                    isUser = false,
                    usedPro = result.usedPro
                )
                replaceLoading(loadingMsg.id, lumiMsg)

                memory.add(Interaction(
                    userText = userText,
                    assistantText = result.response.text,
                    imageBase64 = imageBase64,
                    usedProModel = result.usedPro
                ))

                // Clear latest image after it's been used
                latestImageBase64 = null

            } catch (e: Exception) {
                Log.e(TAG, "Gemini error", e)
                val errorMsg = ChatMessage(
                    text = "Eroare: ${e.message}",
                    time = now(),
                    isUser = false
                )
                replaceLoading(loadingMsg.id, errorMsg)
                _statusText.postValue("Eroare la procesare")
            } finally {
                _isProcessing.postValue(false)
            }
        }
    }

    fun clearHistory() {
        memory.clear()
        _messages.value = emptyList()
    }

    private fun appendMessage(msg: ChatMessage) {
        val current = _messages.value?.toMutableList() ?: mutableListOf()
        current.add(msg)
        _messages.postValue(current)
    }

    private fun replaceLoading(loadingId: Long, replacement: ChatMessage) {
        val current = _messages.value?.toMutableList() ?: return
        val idx = current.indexOfFirst { it.id == loadingId }
        if (idx >= 0) current[idx] = replacement else current.add(replacement)
        _messages.postValue(current)
    }

    private fun addSystemMessage(text: String) {
        appendMessage(ChatMessage(text = text, time = now(), isUser = false))
    }

    private fun now() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    override fun onCleared() {
        super.onCleared()
        bluetooth.disconnect()
    }
}
