package com.lumi.app.settings

import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lumi.app.databinding.ActivitySettingsBinding
import com.lumi.app.ui.PatchNotesActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AppSettings

    // Receives broadcasts when device info (theme) changes while this screen is open.
    private val deviceInfoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshDeviceSection()
    }

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
        binding.layoutAutonomous.visibility = if (settings.actionModeEnabled) View.VISIBLE else View.GONE
        binding.switchAutonomous.isChecked = settings.autonomousMode
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
        binding.switchStreaming.isChecked = settings.streamingEnabled

        // Lumi device section populated in onResume() so it stays fresh.

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
        binding.btnPatchNotes.setOnClickListener {
            startActivity(Intent(this, PatchNotesActivity::class.java))
        }
        binding.btnSetupFingerprint.setOnClickListener {
            // Delegate to MainActivity's ViewModel which holds the BLE connection
            val intent = Intent("com.lumi.app.SETUP_FINGERPRINT")
            sendBroadcast(intent)
            Toast.makeText(this, "Comandă trimisă dispozitivului.", Toast.LENGTH_SHORT).show()
        }
        setupActionModeSwitch()
    }

    private fun setupActionModeSwitch() {
        var ignoreNext = false
        binding.switchActionMode.setOnCheckedChangeListener { _, checked ->
            if (ignoreNext) return@setOnCheckedChangeListener
            if (checked) {
                AlertDialog.Builder(this)
                    .setTitle("Mod Acţiune — Informaţii")
                    .setMessage(
                        "Ce poate face Lumi:\n" +
                        "• Trimite mesaje (WhatsApp, SMS, Instagram, Snapchat, Discord)\n" +
                        "• Efectua apeluri telefonice\n" +
                        "• Seta timere, alarme şi cronometru\n" +
                        "• Naviga cu Google Maps sau Waze\n" +
                        "• Controla VPN, luminozitate, volum, Do Not Disturb\n" +
                        "• Controla redarea media (play/pauza/skip pe Spotify, Netflix etc.)\n" +
                        "• Salva notiţe şi crea/citi evenimente calendar\n" +
                        "• Deschide aplicaţii bancare (Revolut, BTpay, PayPal) — DOAR vizualizare\n" +
                        "• Căuta produse pe Amazon, eBay, AliExpress\n" +
                        "• Deschide aplicaţii crypto (Binance, Coinbase) — DOAR vizualizare sold\n" +
                        "• Deschide Google News, Strava, Google Fit\n\n" +
                        "Ce NU va face Lumi niciodată:\n" +
                        "• Nu plasează comenzi sau cumpărături\n" +
                        "• Nu efectuează transferuri bancare sau crypto de niciun fel\n" +
                        "• Nu trimite mesaje fără confirmarea ta (dacă Mod Autonom e dezactivat)\n\n" +
                        "Ai controlul total. Apasă Anulează oricând."
                    )
                    .setPositiveButton("Am înţeles, activează") { _, _ ->
                        binding.layoutAutonomous.visibility = View.VISIBLE
                    }
                    .setNegativeButton("Anulează") { _, _ ->
                        ignoreNext = true
                        binding.switchActionMode.isChecked = false
                        ignoreNext = false
                        binding.layoutAutonomous.visibility = View.GONE
                    }
                    .setCancelable(false)
                    .show()
            } else {
                binding.layoutAutonomous.visibility = View.GONE
                binding.switchAutonomous.isChecked = false
            }
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
        settings.autonomousMode    = binding.switchAutonomous.isChecked
        settings.memorySizeHistory = binding.seekBarMemory.progress
        settings.systemPrompt = binding.etSystemPrompt.text?.toString() ?: AppSettings.DEFAULT_SYSTEM_PROMPT
        settings.autoConnect = binding.switchAutoConnect.isChecked
        settings.streamingEnabled = binding.switchStreaming.isChecked
        settings.fingerprintEnabled = binding.switchFingerprint.isChecked

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

    override fun onResume() {
        super.onResume()
        refreshDeviceSection()
        val filter = IntentFilter("com.lumi.app.DEVICE_INFO_UPDATED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(deviceInfoReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(deviceInfoReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(deviceInfoReceiver) } catch (_: IllegalArgumentException) {}
    }

    private fun refreshDeviceSection() {
        val theme = if (settings.hasBtDevice()) settings.getDeviceTheme(settings.btDeviceAddress) else "grey"
        binding.tvDeviceTheme.text = "Temă dispozitiv: $theme"
        binding.switchFingerprint.isChecked = settings.fingerprintEnabled
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    data class BluetoothDeviceItem(val name: String, val address: String) {
        override fun toString() = "$name ($address)"
    }
}
