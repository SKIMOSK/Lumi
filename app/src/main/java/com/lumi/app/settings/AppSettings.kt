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

        const val OPENROUTER_DEFAULT_URL = "https://openrouter.ai/api/v1"

        const val MODEL_FASTER        = "google/gemini-2.0-flash"        // fastest — no thinking overhead
        const val MODEL_FAST          = "google/gemini-2.5-flash"
        const val MODEL_EXPERT_PRO    = "google/gemini-2.5-pro-preview"
        const val MODEL_EXPERT_OPUS   = "anthropic/claude-opus-4-7"
        const val MODEL_EXPERT_SONNET = "anthropic/claude-sonnet-4-6"

        val FAST_MODELS  = listOf(MODEL_FASTER, MODEL_FAST)
        val FAST_LABELS  = listOf("Fastest (Gemini 2.0 Flash)", "Fast (Gemini 2.5 Flash)")
        val EXPERT_MODELS  = listOf(MODEL_EXPERT_PRO, MODEL_EXPERT_OPUS, MODEL_EXPERT_SONNET)
        val EXPERT_LABELS  = listOf("Balanced — Gemini 2.5 Pro", "Higher End — Claude Opus 4", "Cost Efficient — Claude Sonnet")

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
        get() = prefs.getString(KEY_FAST_MODEL, MODEL_FASTER) ?: MODEL_FASTER
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

    /** Fully autonomous: act silently like a smartwatch, no confirmation dialogs. Only valid when actionModeEnabled=true. */
    var autonomousMode: Boolean
        get() = prefs.getBoolean(KEY_AUTONOMOUS_MODE, false) && actionModeEnabled
        set(v) = prefs.edit().putBoolean(KEY_AUTONOMOUS_MODE, v).apply()

    fun hasApiKey() = openRouterApiKey.isNotBlank()
    fun hasBtDevice() = btDeviceAddress.isNotBlank()
}
