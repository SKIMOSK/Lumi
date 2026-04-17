package com.lumi.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

class AppSettings(context: Context) {

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    companion object {
        const val KEY_GEMINI_API_KEY = "gemini_api_key"
        const val KEY_FAST_MODEL = "fast_model"
        const val KEY_PRO_MODEL = "pro_model"
        const val KEY_BT_DEVICE_ADDRESS = "bt_device_address"
        const val KEY_BT_DEVICE_NAME = "bt_device_name"
        const val KEY_STT_LANGUAGE = "stt_language"
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        const val KEY_AUTO_CONNECT = "auto_connect"

        const val MODEL_FLASH_15 = "gemini-1.5-flash"
        const val MODEL_FLASH_25 = "gemini-2.5-flash-preview-04-17"
        const val MODEL_PRO_25 = "gemini-2.5-pro-preview-03-25"

        val FAST_MODELS = listOf(MODEL_FLASH_15, MODEL_FLASH_25)
        val FAST_MODEL_LABELS = listOf("Gemini 1.5 Flash", "Gemini 2.5 Flash")

        const val DEFAULT_SYSTEM_PROMPT = """Ești Lumi, un asistent AI personal integrat în dispozitivul Lumi.
Răspunzi în română (sau în limba în care ți se vorbește).
Ești concis și util. Poți vedea imagini trimise de la dispozitivul Lumi.
Ai acces la notificările telefonului.
Preferă răspunsuri scurte pentru întrebări simple.
Pentru sarcini complexe, orchestrezi acțiunile pas cu pas."""
    }

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value).apply()

    var fastModel: String
        get() = prefs.getString(KEY_FAST_MODEL, MODEL_FLASH_25) ?: MODEL_FLASH_25
        set(value) = prefs.edit().putString(KEY_FAST_MODEL, value).apply()

    var proModel: String
        get() = prefs.getString(KEY_PRO_MODEL, MODEL_PRO_25) ?: MODEL_PRO_25
        set(value) = prefs.edit().putString(KEY_PRO_MODEL, value).apply()

    var btDeviceAddress: String
        get() = prefs.getString(KEY_BT_DEVICE_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BT_DEVICE_ADDRESS, value).apply()

    var btDeviceName: String
        get() = prefs.getString(KEY_BT_DEVICE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BT_DEVICE_NAME, value).apply()

    var sttLanguage: String
        get() = prefs.getString(KEY_STT_LANGUAGE, "ro-RO") ?: "ro-RO"
        set(value) = prefs.edit().putString(KEY_STT_LANGUAGE, value).apply()

    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit().putString(KEY_SYSTEM_PROMPT, value).apply()

    var autoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CONNECT, value).apply()

    fun hasApiKey() = geminiApiKey.isNotBlank()
    fun hasBtDevice() = btDeviceAddress.isNotBlank()
}
