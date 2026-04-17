package com.lumi.app.settings

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lumi.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        settings = AppSettings(this)
        populateUi()
        setupListeners()
    }

    private fun populateUi() {
        // Provider toggle
        binding.switchOpenRouter.isChecked = settings.useOpenRouter
        updateProviderVisibility(settings.useOpenRouter)

        // API keys
        binding.etApiKey.setText(settings.geminiApiKey)
        binding.etOpenRouterKey.setText(settings.openRouterApiKey)
        binding.etOpenRouterUrl.setText(
            settings.openRouterBaseUrl.takeIf { it != AppSettings.OPENROUTER_DEFAULT_URL } ?: ""
        )
        binding.etOpenRouterUrl.hint = AppSettings.OPENROUTER_DEFAULT_URL

        binding.etSystemPrompt.setText(settings.systemPrompt)
        binding.switchAutoConnect.isChecked = settings.autoConnect

        // Fast model spinner
        val fastAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, AppSettings.FAST_MODEL_LABELS)
        fastAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerFastModel.adapter = fastAdapter
        binding.spinnerFastModel.setSelection(AppSettings.FAST_MODELS.indexOf(settings.fastModel).coerceAtLeast(0))

        // STT language
        val languages = listOf("ro-RO", "en-US", "fr-FR", "de-DE", "es-ES")
        val langAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSttLanguage.adapter = langAdapter
        binding.spinnerSttLanguage.setSelection(languages.indexOf(settings.sttLanguage).coerceAtLeast(0))

        // BT device
        binding.tvBtDevice.text = if (settings.hasBtDevice())
            "${settings.btDeviceName} (${settings.btDeviceAddress})"
        else "Niciun dispozitiv selectat"

        loadPairedDevices()
    }

    private fun setupListeners() {
        binding.switchOpenRouter.setOnCheckedChangeListener { _, checked ->
            updateProviderVisibility(checked)
        }
        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnScanBt.setOnClickListener { scanBluetooth() }
        binding.btnResetPrompt.setOnClickListener {
            binding.etSystemPrompt.setText(AppSettings.DEFAULT_SYSTEM_PROMPT)
        }
    }

    private fun updateProviderVisibility(useOpenRouter: Boolean) {
        binding.layoutGeminiDirect.visibility = if (useOpenRouter) View.GONE else View.VISIBLE
        binding.layoutOpenRouter.visibility = if (useOpenRouter) View.VISIBLE else View.GONE
    }

    private fun saveSettings() {
        val useOpenRouter = binding.switchOpenRouter.isChecked

        if (useOpenRouter) {
            val orKey = binding.etOpenRouterKey.text.toString().trim()
            if (orKey.isBlank()) {
                Toast.makeText(this, "Cheia API OpenRouter nu poate fi goală.", Toast.LENGTH_SHORT).show()
                return
            }
            settings.openRouterApiKey = orKey
            val customUrl = binding.etOpenRouterUrl.text.toString().trim()
            settings.openRouterBaseUrl = customUrl.ifBlank { AppSettings.OPENROUTER_DEFAULT_URL }
        } else {
            val geminiKey = binding.etApiKey.text.toString().trim()
            if (geminiKey.isBlank()) {
                Toast.makeText(this, "Cheia API Gemini nu poate fi goală.", Toast.LENGTH_SHORT).show()
                return
            }
            settings.geminiApiKey = geminiKey
        }

        settings.useOpenRouter = useOpenRouter
        settings.systemPrompt = binding.etSystemPrompt.text.toString()
        settings.autoConnect = binding.switchAutoConnect.isChecked
        settings.fastModel = AppSettings.FAST_MODELS[binding.spinnerFastModel.selectedItemPosition]

        val languages = listOf("ro-RO", "en-US", "fr-FR", "de-DE", "es-ES")
        settings.sttLanguage = languages[binding.spinnerSttLanguage.selectedItemPosition]

        val selectedDevice = binding.spinnerBtDevices.selectedItem as? BluetoothDeviceItem
        if (selectedDevice != null) {
            settings.btDeviceAddress = selectedDevice.address
            settings.btDeviceName = selectedDevice.name
        }

        Toast.makeText(this, "Setările au fost salvate.", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun loadPairedDevices() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter ?: return

        val devices = try {
            adapter.bondedDevices?.map { device ->
                BluetoothDeviceItem(
                    name = try { device.name ?: "Necunoscut" } catch (e: SecurityException) { "?" },
                    address = device.address
                )
            } ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }

        val deviceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, devices)
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerBtDevices.adapter = deviceAdapter

        val currentIdx = devices.indexOfFirst { it.address == settings.btDeviceAddress }
        if (currentIdx >= 0) binding.spinnerBtDevices.setSelection(currentIdx)
    }

    private fun scanBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Toast.makeText(this, "Pornește scanarea din ecranul principal.", Toast.LENGTH_SHORT).show()
        }
        loadPairedDevices()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    data class BluetoothDeviceItem(val name: String, val address: String) {
        override fun toString() = "$name ($address)"
    }
}
