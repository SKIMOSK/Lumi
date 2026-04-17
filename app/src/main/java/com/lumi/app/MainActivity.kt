package com.lumi.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lumi.app.bluetooth.LumiBluetoothManager
import com.lumi.app.databinding.ActivityMainBinding
import com.lumi.app.notifications.LumiNotificationService
import com.lumi.app.settings.SettingsActivity
import com.lumi.app.stt.RomanianSTT
import com.lumi.app.ui.MessageAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: com.lumi.app.ui.MainViewModel by viewModels()
    private lateinit var adapter: MessageAdapter
    private lateinit var stt: RomanianSTT
    private var isListening = false

    // ─── Permissions ──────────────────────────────────────────────────────────

    private val requiredPermissions get() = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(this, "Unele permisiuni au fost refuzate: ${denied.joinToString()}", Toast.LENGTH_LONG).show()
        }
        checkNotificationListenerAccess()
        initBluetooth()
    }

    private val btEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (viewModel.bluetooth.isBluetoothEnabled()) initBluetooth()
        else Toast.makeText(this, "Bluetooth-ul este necesar pentru Lumi.", Toast.LENGTH_SHORT).show()
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        setupButtons()
        observeViewModel()

        stt = RomanianSTT(this).also {
            it.setLanguage(viewModel.settings.sttLanguage)
        }

        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updateNotificationBadge()
    }

    override fun onDestroy() {
        stt.destroy()
        super.onDestroy()
    }

    // ─── Setup ────────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = MessageAdapter()
        val layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.layoutManager = layoutManager
        binding.rvMessages.adapter = adapter
    }

    private fun setupButtons() {
        // Mic / voice button — long-press to speak, tap to stop
        binding.fabMic.setOnClickListener {
            if (isListening) stopListening() else startListening()
        }

        // Send text button
        binding.btnSend.setOnClickListener {
            val text = binding.etInput.text?.toString()?.trim() ?: ""
            if (text.isNotBlank()) {
                binding.etInput.text?.clear()
                viewModel.processPrompt(text)
            }
        }

        // BT connect button
        binding.btnConnect.setOnClickListener {
            when (viewModel.btState.value) {
                LumiBluetoothManager.ConnectionState.DISCONNECTED -> connectBluetooth()
                LumiBluetoothManager.ConnectionState.CONNECTED -> viewModel.disconnectBluetooth()
                else -> {}
            }
        }
    }

    private fun observeViewModel() {
        viewModel.messages.observe(this) { msgs ->
            adapter.submitList(msgs) {
                if (msgs.isNotEmpty()) binding.rvMessages.scrollToPosition(msgs.size - 1)
            }
        }

        viewModel.btState.observe(this) { state ->
            val (icon, label) = when (state) {
                LumiBluetoothManager.ConnectionState.CONNECTED ->
                    android.R.drawable.stat_sys_data_bluetooth to "Conectat"
                LumiBluetoothManager.ConnectionState.SCANNING,
                LumiBluetoothManager.ConnectionState.CONNECTING ->
                    android.R.drawable.stat_notify_sync to "Conectare…"
                else ->
                    android.R.drawable.ic_menu_close_clear_cancel to "Deconectat"
            }
            binding.tvBtStatus.text = label
            binding.btnConnect.text = if (state == LumiBluetoothManager.ConnectionState.CONNECTED) "Deconectare" else "Conectare"
        }

        viewModel.statusText.observe(this) { binding.tvStatus.text = it }

        viewModel.isProcessing.observe(this) { processing ->
            binding.progressBar.visibility = if (processing) View.VISIBLE else View.GONE
            binding.fabMic.isEnabled = !processing
            binding.btnSend.isEnabled = !processing
        }
    }

    // ─── STT ─────────────────────────────────────────────────────────────────

    private fun startListening() {
        if (!stt.isAvailable()) {
            Toast.makeText(this, "STT nu este disponibil pe acest dispozitiv.", Toast.LENGTH_SHORT).show()
            return
        }
        isListening = true
        binding.fabMic.setImageResource(android.R.drawable.ic_media_pause)
        binding.tvStatus.text = "Ascult…"

        lifecycleScope.launch {
            try {
                val text = stt.listenOnce(
                    onPartialResult = { partial ->
                        runOnUiThread { binding.etInput.setText(partial) }
                    },
                    onReadyForSpeech = {
                        runOnUiThread { binding.tvStatus.text = "Vorbește acum…" }
                    }
                )
                isListening = false
                binding.fabMic.setImageResource(android.R.drawable.ic_btn_speak_now)
                if (text.isNotBlank()) {
                    binding.etInput.text?.clear()
                    viewModel.processPrompt(text)
                } else {
                    binding.tvStatus.text = "Nu s-a detectat vorbire."
                }
            } catch (e: Exception) {
                isListening = false
                binding.fabMic.setImageResource(android.R.drawable.ic_btn_speak_now)
                binding.tvStatus.text = "Eroare STT: ${e.message}"
            }
        }
    }

    private fun stopListening() {
        stt.destroy()
        stt = RomanianSTT(this).also { it.setLanguage(viewModel.settings.sttLanguage) }
        isListening = false
        binding.fabMic.setImageResource(android.R.drawable.ic_btn_speak_now)
    }

    // ─── Bluetooth ────────────────────────────────────────────────────────────

    private fun initBluetooth() {
        val btAdapter = viewModel.bluetooth.adapter
        if (btAdapter == null) {
            Toast.makeText(this, "Acest dispozitiv nu suportă Bluetooth.", Toast.LENGTH_LONG).show()
            return
        }
        if (!viewModel.bluetooth.isBluetoothEnabled()) {
            btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (viewModel.settings.autoConnect && viewModel.settings.hasBtDevice()) {
            viewModel.connectBluetooth()
        }
    }

    private fun connectBluetooth() {
        if (!viewModel.bluetooth.isBluetoothEnabled()) {
            btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        viewModel.connectBluetooth()
    }

    // ─── Permissions ─────────────────────────────────────────────────────────

    private fun requestPermissionsIfNeeded() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            checkNotificationListenerAccess()
            initBluetooth()
        }
    }

    private fun checkNotificationListenerAccess() {
        if (!LumiNotificationService.isEnabled(this)) {
            AlertDialog.Builder(this)
                .setTitle("Acces la notificări")
                .setMessage("Lumi are nevoie de acces la notificări pentru a putea citi mesajele și alertele tale. Activează acum?")
                .setPositiveButton("Activează") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Mai târziu", null)
                .show()
        }
    }

    private fun updateNotificationBadge() {
        val enabled = LumiNotificationService.isEnabled(this)
        binding.tvNotifStatus.text = if (enabled) "Notificări: ON" else "Notificări: OFF"
    }

    // ─── Menu ─────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_clear -> {
                viewModel.clearHistory()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
