package com.lumi.app.settings

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lumi.app.bluetooth.LumiBluetoothManager
import com.lumi.app.databinding.ActivitySettingsBinding
import com.lumi.app.ui.PatchNotesActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AppSettings

    private val discoveredDevices = mutableListOf<BluetoothDeviceItem>()
    private var scanAdapter: ArrayAdapter<BluetoothDeviceItem>? = null
    private var leScanCallback: ScanCallback? = null
    private val scanHandler = Handler(Looper.getMainLooper())
    private val scanTimeoutMs = 10_000L

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

        val fastAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, AppSettings.FAST_LABELS)
        fastAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerFastModel.adapter = fastAdapter
        binding.spinnerFastModel.setSelection(AppSettings.FAST_MODELS.indexOf(settings.fastModel).coerceAtLeast(0))

        val expertAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, AppSettings.EXPERT_LABELS)
        expertAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerExpertModel.adapter = expertAdapter
        binding.spinnerExpertModel.setSelection(AppSettings.EXPERT_MODELS.indexOf(settings.expertModel).coerceAtLeast(0))

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

        binding.tvBtDevice.text = if (settings.hasBtDevice())
            "Salvat: ${settings.btDeviceName} (${settings.btDeviceAddress})"
        else "Niciun dispozitiv salvat"

        // Pre-populate scan results list with the currently saved device (if any)
        discoveredDevices.clear()
        if (settings.hasBtDevice()) {
            discoveredDevices.add(BluetoothDeviceItem(settings.btDeviceName, settings.btDeviceAddress))
        }
        scanAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, discoveredDevices)
        scanAdapter!!.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerBtDevices.adapter = scanAdapter
    }

    private fun setupListeners() {
        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnScanBt.setOnClickListener { startBleScan() }
        binding.btnResetPrompt.setOnClickListener {
            binding.etSystemPrompt.setText(AppSettings.DEFAULT_SYSTEM_PROMPT)
        }
        binding.btnPatchNotes.setOnClickListener {
            startActivity(Intent(this, PatchNotesActivity::class.java))
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

    // ─── BLE Device Scanning ──────────────────────────────────────────────────

    private fun startBleScan() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = btManager.adapter ?: run {
            Toast.makeText(this, "Bluetooth nu este disponibil.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!btAdapter.isEnabled) {
            Toast.makeText(this, "Activați Bluetooth mai întâi.", Toast.LENGTH_SHORT).show()
            return
        }

        val permOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        if (!permOk) {
            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            requestPermissions(perm, REQ_BLE_SCAN)
            return
        }

        val leScanner = btAdapter.bluetoothLeScanner ?: run {
            Toast.makeText(this, "BLE scanner indisponibil.", Toast.LENGTH_SHORT).show()
            return
        }

        // Clear previous scan results, keep saved device at top so user can re-select it
        val savedAddress = settings.btDeviceAddress
        discoveredDevices.clear()
        val seenAddresses = mutableSetOf<String>()
        if (settings.hasBtDevice()) {
            discoveredDevices.add(BluetoothDeviceItem(settings.btDeviceName, savedAddress))
            seenAddresses.add(savedAddress)
        }
        scanAdapter?.notifyDataSetChanged()

        binding.btnScanBt.isEnabled = false
        binding.btnScanBt.text = "Se caută... (10s)"

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(LumiBluetoothManager.LUMI_SERVICE_UUID))
            .build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address ?: return
                if (!seenAddresses.add(address)) return
                val name = try {
                    result.device.name ?: result.scanRecord?.deviceName ?: "Lumi"
                } catch (_: SecurityException) { "Lumi" }
                runOnUiThread {
                    discoveredDevices.add(BluetoothDeviceItem(name, address))
                    scanAdapter?.notifyDataSetChanged()
                }
            }
            override fun onScanFailed(errorCode: Int) {
                runOnUiThread { stopBleScan(leScanner) }
            }
        }
        leScanCallback = cb

        try {
            leScanner.startScan(listOf(filter), scanSettings, cb)
        } catch (e: SecurityException) {
            leScanCallback = null
            binding.btnScanBt.isEnabled = true
            binding.btnScanBt.text = "Caută dispozitive Lumi (BLE)"
            Toast.makeText(this, "Permisiune Bluetooth lipsă.", Toast.LENGTH_SHORT).show()
            return
        }

        scanHandler.postDelayed({ stopBleScan(leScanner) }, scanTimeoutMs)
    }

    private fun stopBleScan(
        leScanner: android.bluetooth.le.BluetoothLeScanner? =
            (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner
    ) {
        scanHandler.removeCallbacksAndMessages(null)
        leScanCallback?.let {
            try { leScanner?.stopScan(it) } catch (_: SecurityException) {}
            leScanCallback = null
        }
        if (!isDestroyed) {
            binding.btnScanBt.isEnabled = true
            binding.btnScanBt.text = "Caută dispozitive Lumi (BLE)"
            if (discoveredDevices.isEmpty()) {
                Toast.makeText(this, "Nu s-au găsit dispozitive Lumi.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLE_SCAN && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startBleScan()
        } else if (requestCode == REQ_BLE_SCAN) {
            Toast.makeText(this, "Permisiunile BLE sunt necesare pentru scanare.", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Save / Load ──────────────────────────────────────────────────────────

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

        val selectedDevice = binding.spinnerBtDevices.selectedItem as? BluetoothDeviceItem
        if (selectedDevice != null) {
            settings.btDeviceAddress = selectedDevice.address
            settings.btDeviceName = selectedDevice.name
        }

        Toast.makeText(this, "Setările au fost salvate.", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleScan()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    data class BluetoothDeviceItem(val name: String, val address: String) {
        override fun toString() = "$name ($address)"
    }

    companion object {
        private const val REQ_BLE_SCAN = 1001
    }
}
