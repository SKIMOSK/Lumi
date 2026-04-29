package com.lumi.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

class AppSettings(context: Context) {

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    companion object {
        const val KEY_OPENROUTER_API_KEY  = "openrouter_api_key"
        const val KEY_OPENROUTER_BASE_URL = "openrouter_base_url"
        const val KEY_FAST_MODEL          = "fast_model"
        const val KEY_EXPERT_MODEL        = "expert_model"
        const val KEY_BT_DEVICE_ADDRESS   = "bt_device_address"
        const val KEY_BT_DEVICE_NAME      = "bt_device_name"
        const val KEY_STT_LANGUAGE        = "stt_language"
        const val KEY_SYSTEM_PROMPT       = "system_prompt"
        const val KEY_AUTO_CONNECT        = "auto_connect"
        const val KEY_MEMORY_SIZE         = "memory_size"
        const val KEY_ACTION_MODE         = "action_mode"
        const val KEY_AUTONOMOUS_MODE     = "autonomous_mode"
        const val KEY_TTS_SPEED           = "tts_speed"
        const val KEY_STREAMING                = "streaming_enabled"
        const val KEY_FINGERPRINT_ENABLED      = "device_fingerprint_enabled"
        const val KEY_TRANSLATION_FROM         = "translation_from_lang"
        const val KEY_TRANSLATION_TO           = "translation_to_lang"
        private const val KEY_DEVICE_THEME_PREFIX = "device_theme_"

        const val OPENROUTER_DEFAULT_URL = "https://openrouter.ai/api/v1"

        const val MODEL_FASTER        = "anthropic/claude-haiku-4-5"          // fixed: no date suffix
        const val MODEL_FAST          = "google/gemini-2.5-flash"
        const val MODEL_EXPERT_PRO    = "google/gemini-3.1-pro-preview"
        const val MODEL_EXPERT_OPUS   = "anthropic/claude-opus-4-6"
        const val MODEL_EXPERT_SONNET = "anthropic/claude-sonnet-4-6"

        val FAST_MODELS  = listOf(MODEL_FASTER, MODEL_FAST)
        val FAST_LABELS  = listOf("Fastest (Claude Haiku 4.5)", "Fast (Gemini 2.5 Flash)")
        val EXPERT_MODELS  = listOf(MODEL_EXPERT_PRO, MODEL_EXPERT_OPUS, MODEL_EXPERT_SONNET)
        val EXPERT_LABELS  = listOf("Balanced — Gemini 3.1 Pro", "Higher End — Claude Opus 4.6", "Cost Efficient — Claude Sonnet")

        const val DEFAULT_SYSTEM_PROMPT = """Ești Lumi, un asistent AI personal integrat în dispozitivul Lumi.
Răspunzi în română (sau în limba în care ți se vorbește).
Ești concis și util. Poți vedea imagini trimise de la dispozitivul Lumi."""
    }

    var openRouterApiKey: String
        get() = prefs.getString(KEY_OPENROUTER_API_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_OPENROUTER_API_KEY, v).apply()

    var openRouterBaseUrl: String
        get() = prefs.getString(KEY_OPENROUTER_BASE_URL, OPENROUTER_DEFAULT_URL) ?: OPENROUTER_DEFAULT_URL
        set(v) = prefs.edit().putString(KEY_OPENROUTER_BASE_URL, v).apply()

    var fastModel: String
        get() {
            val v = prefs.getString(KEY_FAST_MODEL, MODEL_FAST) ?: MODEL_FAST
            // Migrate from old broken date-suffixed Haiku ID
            return if (v == "anthropic/claude-haiku-4-5-20251001") MODEL_FASTER else v
        }
        set(v) = prefs.edit().putString(KEY_FAST_MODEL, v).apply()

    var expertModel: String
        get() = prefs.getString(KEY_EXPERT_MODEL, MODEL_EXPERT_PRO) ?: MODEL_EXPERT_PRO
        set(v) = prefs.edit().putString(KEY_EXPERT_MODEL, v).apply()

    var btDeviceAddress: String
        get() = prefs.getString(KEY_BT_DEVICE_ADDRESS, "") ?: ""
        set(v) = prefs.edit().putString(KEY_BT_DEVICE_ADDRESS, v).apply()

    var btDeviceName: String
        get() = prefs.getString(KEY_BT_DEVICE_NAME, "") ?: ""
        set(v) = prefs.edit().putString(KEY_BT_DEVICE_NAME, v).apply()

    var sttLanguage: String
        get() = prefs.getString(KEY_STT_LANGUAGE, "ro-RO") ?: "ro-RO"
        set(v) = prefs.edit().putString(KEY_STT_LANGUAGE, v).apply()

    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(v) = prefs.edit().putString(KEY_SYSTEM_PROMPT, v).apply()

    var autoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONNECT, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_CONNECT, v).apply()

    /** 0 = only current query in context; 5 = last 5 turns + current (default). Max 10. */
    var memorySizeHistory: Int
        get() = prefs.getInt(KEY_MEMORY_SIZE, 5)
        set(v) = prefs.edit().putInt(KEY_MEMORY_SIZE, v.coerceIn(0, 10)).apply()

    var actionModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_ACTION_MODE, false)
        set(v) = prefs.edit().putBoolean(KEY_ACTION_MODE, v).apply()

    var ttsSpeed: Float
        get() = prefs.getFloat(KEY_TTS_SPEED, 1.0f)
        set(v) = prefs.edit().putFloat(KEY_TTS_SPEED, v.coerceIn(0.5f, 2.0f)).apply()

    /** Fully autonomous: act silently like a smartwatch, no confirmation dialogs. Only valid when actionModeEnabled=true. */
    var autonomousMode: Boolean
        get() = prefs.getBoolean(KEY_AUTONOMOUS_MODE, false) && actionModeEnabled
        set(v) = prefs.edit().putBoolean(KEY_AUTONOMOUS_MODE, v).apply()

    /** Stream AI tokens to the chat UI as they arrive (only the visible reply, not the action JSON). */
    var streamingEnabled: Boolean
        get() = prefs.getBoolean(KEY_STREAMING, false)
        set(v) = prefs.edit().putBoolean(KEY_STREAMING, v).apply()

    var fingerprintEnabled: Boolean
        get() = prefs.getBoolean(KEY_FINGERPRINT_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_FINGERPRINT_ENABLED, v).apply()

    /** Source language for real-time translation ("auto" = detect automatically). */
    var translationFromLang: String
        get() = prefs.getString(KEY_TRANSLATION_FROM, "auto") ?: "auto"
        set(v) = prefs.edit().putString(KEY_TRANSLATION_FROM, v).apply()

    /** Target language for real-time translation (default: app STT language). */
    var translationToLang: String
        get() = prefs.getString(KEY_TRANSLATION_TO, sttLanguage) ?: sttLanguage
        set(v) = prefs.edit().putString(KEY_TRANSLATION_TO, v).apply()

    fun getDeviceTheme(address: String): String {
        val key = KEY_DEVICE_THEME_PREFIX + address.replace(":", "_")
        return prefs.getString(key, "grey") ?: "grey"
    }

    fun setDeviceTheme(address: String, theme: String) {
        val key = KEY_DEVICE_THEME_PREFIX + address.replace(":", "_")
        prefs.edit().putString(key, theme).apply()
    }

    fun hasApiKey() = openRouterApiKey.isNotBlank()
    fun hasBtDevice() = btDeviceAddress.isNotBlank()
}
