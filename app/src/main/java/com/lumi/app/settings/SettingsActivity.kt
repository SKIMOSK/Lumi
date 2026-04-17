package com.lumi.app.settings

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.SeekBar
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
        binding.etOpenRouterKey.setText(settings.openRouterApiKey)
        val customUrl = settings.openRouterBaseUrl.takeIf { it != AppSettings.OPENROUTER_DEFAULT_URL } ?: ""
        binding.etOpenRouterUrl.setText(customUrl)

        // Fast model spinner
        val fastAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, AppSettings.FAST_LABELS)
        fastAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerFastModel.adapter = fastAdapter
        binding.spinnerFastModel.setSelection(AppSettings.FAST_MODELS.indexOf(settings.fastModel).coerceAtLeast(0))

        // Expert model spinner
        val expertAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, AppSettings.EXPERT_LABELS)
        expertAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerExpertModel.adapter = expertAdapter
        binding.spinnerExpertModel.setSelection(AppSettings.EXPERT_MODELS.indexOf(settings.expertModel).coerceAtLeast(0))

        // STT language
        val languages = listOf("ro-RO", "en-US", "fr-FR", "de-DE", "es-ES")
        val langAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSttLanguage.adapter = langAdapter
        binding.spinnerSttLanguage.setSelection(languages.indexOf(settings.sttLanguage).coerceAtLeast(0))

        binding.switchActionMode.isChecked = settings.actionModeEnabled
        binding.seekBarMemory.progress = settings.memorySizeHistory
        binding.tvMemorySize.text = "Tururi reținute: ${settings.memorySizeHistory}"
        binding.seekBarMemory.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                binding.tvMemorySize.text = "Tururi reținute: $progress"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.etSystemPrompt.setText(settings.systemPrompt)
        binding.switchAutoConnect.isChecked = settings.autoConnect

        binding.tvBtDevice.text = if (settings.hasBtDevice())
            "${settings.btDeviceName} (${settings.btDeviceAddress})"
        else "Niciun dispozitiv selectat"

        loadPairedDevices()
    }

    private fun setupListeners() {
        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnScanBt.setOnClickListener { loadPairedDevices() }
        binding.btnResetPrompt.setOnClickListener {
            binding.etSystemPrompt.setText(AppSettings.DEFAULT_SYSTEM_PROMPT)
        }
    }

    private fun saveSettings() {
        val apiKey = binding.etOpenRouterKey.text?.toString()?.trim() ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Cheia API OpenRouter nu poate fi goală.", Toast.LENGTH_SHORT).show()
            return
        }
        settings.openRouterApiKey = apiKey

        val customUrl = binding.etOpenRouterUrl.text?.toString()?.trim() ?: ""
        settings.openRouterBaseUrl = customUrl.ifBlank { AppSettings.OPENROUTER_DEFAULT_URL }

        settings.fastModel = AppSettings.FAST_MODELS[binding.spinnerFastModel.selectedItemPosition]
        settings.expertModel = AppSettings.EXPERT_MODELS[binding.spinnerExpertModel.selectedItemPosition]

        val languages = listOf("ro-RO", "en-US", "fr-FR", "de-DE", "es-ES")
        settings.sttLanguage = languages[binding.spinnerSttLanguage.selectedItemPosition]

        settings.actionModeEnabled = binding.switchActionMode.isChecked
        settings.memorySizeHistory = binding.seekBarMemory.progress
        settings.systemPrompt = binding.etSystemPrompt.text?.toString() ?: AppSettings.DEFAULT_SYSTEM_PROMPT
        settings.autoConnect = binding.switchAutoConnect.isChecked

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
        } catch (e: SecurityException) { emptyList() }

        val deviceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, devices)
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerBtDevices.adapter = deviceAdapter

        val currentIdx = devices.indexOfFirst { it.address == settings.btDeviceAddress }
        if (currentIdx >= 0) binding.spinnerBtDevices.setSelection(currentIdx)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    data class BluetoothDeviceItem(val name: String, val address: String) {
        override fun toString() = "$name ($address)"
    }
}
