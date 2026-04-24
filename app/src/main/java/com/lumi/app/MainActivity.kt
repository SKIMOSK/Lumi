package com.lumi.app

import android.Manifest
import android.animation.ValueAnimator
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
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
import com.lumi.app.ui.ChatMessage
import com.lumi.app.ui.MainViewModel
import com.lumi.app.ui.MessageAdapter
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: MessageAdapter
    private lateinit var stt: RomanianSTT
    private var isListening = false
    private var accessibilityDialogShown = false
    private var orbAnimator: ValueAnimator? = null

    // ─── Permissions ──────────────────────────────────────────────────────────

    private val requiredPermissions get() = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.RECEIVE_SMS); add(Manifest.permission.READ_SMS); add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.CALL_PHONE)
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) Toast.makeText(this, "Permisiuni refuzate: ${denied.size}", Toast.LENGTH_SHORT).show()
        checkNotificationAccess()
        initBluetooth()
    }
    private val btEnableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (viewModel.bluetooth.isBluetoothEnabled()) initBluetooth()
    }

    // ─── Image attachment ─────────────────────────────────────────────────────

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { encodeAndAttach(it) }
    }
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp: Bitmap? ->
        bmp?.let { attachBitmap(it) }
    }

    private fun encodeAndAttach(uri: Uri) {
        val bmp = android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
        attachBitmap(bmp)
    }

    private fun attachBitmap(bmp: Bitmap) {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        viewModel.attachImage(b64)
        binding.ivAttachedPreview.setImageBitmap(bmp)
        binding.ivAttachedPreview.visibility = View.VISIBLE
        Toast.makeText(this, "Imagine atașată", Toast.LENGTH_SHORT).show()
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        stt = RomanianSTT(this).also { it.setLanguage(viewModel.settings.sttLanguage) }
        viewModel.consent.stt = stt

        setupRecyclerView()
        setupButtons()
        setupInputWatcher()
        observeViewModel()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updateNotificationBadge()
        viewModel.refreshMemorySize()
        checkAccessibilityService()
        checkManageExternalStorage()
    }

    override fun onDestroy() { orbAnimator?.cancel(); stt.destroy(); super.onDestroy() }

    // ─── Setup ────────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = MessageAdapter(
            context = this,
            scope = lifecycleScope,
            onSpeak = { text -> viewModel.tts.speak(text) }
        )
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.adapter = adapter
    }

    private fun setupButtons() {
        binding.fabMic.setOnClickListener { if (isListening) stopListening() else startListening() }

        binding.btnSend.setOnClickListener {
            val text = binding.etInput.text?.toString()?.trim() ?: ""
            if (text.isNotBlank()) { binding.etInput.text?.clear(); viewModel.processPrompt(text) }
        }

        binding.btnAttach.setOnClickListener { showImageSourcePicker() }

        binding.ivAttachedPreview.setOnClickListener {
            viewModel.attachImage("")
            binding.ivAttachedPreview.visibility = View.GONE
        }

        binding.btnConnect.setOnClickListener {
            when (viewModel.btState.value) {
                LumiBluetoothManager.ConnectionState.DISCONNECTED -> connectBluetooth()
                LumiBluetoothManager.ConnectionState.CONNECTED -> viewModel.disconnectBluetooth()
                else -> {}
            }
        }

        // Settings icon in the new header
        binding.btnHeaderSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Voice overlay buttons
        binding.btnVoiceClose.setOnClickListener { stopListening() }
        binding.btnVoiceCancel.setOnClickListener { stopListening() }
        binding.btnVoiceSend.setOnClickListener {
            val text = binding.etInput.text?.toString()?.trim() ?: ""
            stopListening()
            if (text.isNotBlank()) { binding.etInput.text?.clear(); viewModel.processPrompt(text) }
        }

        // BT chip also triggers connect
        binding.chipBt.setOnClickListener {
            when (viewModel.btState.value) {
                LumiBluetoothManager.ConnectionState.DISCONNECTED -> connectBluetooth()
                LumiBluetoothManager.ConnectionState.CONNECTED -> viewModel.disconnectBluetooth()
                else -> {}
            }
        }
    }

    private fun setupInputWatcher() {
        binding.etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val hasText = !s.isNullOrBlank()
                binding.btnSend.visibility = if (hasText) View.VISIBLE else View.GONE
                binding.fabMic.visibility = if (hasText) View.GONE else View.VISIBLE
            }
        })
    }

    private fun showImageSourcePicker() {
        AlertDialog.Builder(this)
            .setTitle("Atașează imagine")
            .setItems(arrayOf("Cameră", "Galerie")) { _, which ->
                if (which == 0) cameraLauncher.launch(null)
                else galleryLauncher.launch("image/*")
            }.show()
    }

    private fun observeViewModel() {
        viewModel.messages.observe(this) { msgs ->
            adapter.submitList(msgs) {
                if (msgs.isNotEmpty()) binding.rvMessages.scrollToPosition(msgs.size - 1)
            }
        }
        viewModel.btState.observe(this) { state ->
            val statusText = when (state) {
                LumiBluetoothManager.ConnectionState.CONNECTED   -> "Conectat"
                LumiBluetoothManager.ConnectionState.SCANNING,
                LumiBluetoothManager.ConnectionState.CONNECTING  -> "Conectare\u2026"
                else -> "Deconectat"
            }
            binding.tvBtStatus.text = statusText
            binding.btnConnect.text = if (state == LumiBluetoothManager.ConnectionState.CONNECTED) "Deconectare" else "Conectare"
            val chipBg = when (state) {
                LumiBluetoothManager.ConnectionState.CONNECTED -> R.drawable.chip_ok
                LumiBluetoothManager.ConnectionState.SCANNING,
                LumiBluetoothManager.ConnectionState.CONNECTING -> R.drawable.chip_accent
                else -> R.drawable.chip_default
            }
            val chipColor = when (state) {
                LumiBluetoothManager.ConnectionState.CONNECTED -> R.color.color_ok
                LumiBluetoothManager.ConnectionState.SCANNING,
                LumiBluetoothManager.ConnectionState.CONNECTING -> R.color.accent
                else -> R.color.text_hint
            }
            binding.chipBt.text = statusText
            binding.chipBt.background = ContextCompat.getDrawable(this, chipBg)
            binding.chipBt.setTextColor(ContextCompat.getColor(this, chipColor))
        }
        viewModel.statusText.observe(this) { binding.tvStatus.text = it }
        viewModel.isProcessing.observe(this) { processing ->
            binding.progressBar.visibility = if (processing) View.VISIBLE else View.GONE
            binding.fabMic.isEnabled = !processing
            binding.btnSend.isEnabled = !processing
            binding.btnAttach.isEnabled = !processing
        }
        // Consent dialog requests from ViewModel
        viewModel.consentRequest.observe(this) { req ->
            req ?: return@observe
            AlertDialog.Builder(this)
                .setTitle("Confirmare")
                .setMessage(req.message)
                .setPositiveButton("Da") { _, _ -> viewModel.resolveConsent(req.id, true) }
                .setNegativeButton("Nu") { _, _ -> viewModel.resolveConsent(req.id, false) }
                .setCancelable(false)
                .show()
        }
    }

    // ─── STT ─────────────────────────────────────────────────────────────────

    private fun startListening() {
        if (!stt.isAvailable()) { Toast.makeText(this, "STT indisponibil.", Toast.LENGTH_SHORT).show(); return }
        viewModel.tts.stopWithInterrupt()
        isListening = true
        binding.voiceOverlay.visibility = View.VISIBLE
        binding.tvVoiceStatus.text = "ASCULT"
        binding.tvVoiceTranscript.text = "Asculta..."
        startOrbAnimation()
        lifecycleScope.launch {
            val finalText: String = try {
                stt.listenOnce(
                    onPartialResult = { partial ->
                        runOnUiThread {
                            binding.tvVoiceTranscript.text = partial.ifBlank { "Asculta..." }
                            binding.etInput.setText(partial)
                        }
                    },
                    onReadyForSpeech = {
                        runOnUiThread { binding.tvVoiceStatus.text = "VORBESTE" }
                    }
                )
            } catch (e: Exception) {
                ""
            }
            isListening = false
            stopOrbAnimation()
            binding.voiceOverlay.visibility = View.GONE
            if (finalText.isNotBlank()) {
                binding.etInput.text?.clear()
                viewModel.processPrompt(finalText)
            }
        }
    }

    private fun stopListening() {
        stt.destroy(); stt = RomanianSTT(this).also { it.setLanguage(viewModel.settings.sttLanguage) }
        viewModel.consent.stt = stt
        isListening = false
        stopOrbAnimation()
        binding.voiceOverlay.visibility = View.GONE
    }

    // ─── Bluetooth ────────────────────────────────────────────────────────────

    private fun initBluetooth() {
        val adapter = viewModel.bluetooth.adapter ?: return
        if (!viewModel.bluetooth.isBluetoothEnabled()) {
            btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return
        }
        if (viewModel.settings.autoConnect && viewModel.settings.hasBtDevice()) viewModel.connectBluetooth()
    }

    private fun connectBluetooth() {
        if (!viewModel.bluetooth.isBluetoothEnabled()) btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        else viewModel.connectBluetooth()
    }

    // ─── Permissions ─────────────────────────────────────────────────────────

    private fun requestPermissionsIfNeeded() {
        val missing = requiredPermissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
        else { checkNotificationAccess(); initBluetooth() }
    }

    private fun checkNotificationAccess() {
        if (!LumiNotificationService.isEnabled(this)) {
            AlertDialog.Builder(this)
                .setTitle("Acces la notificări")
                .setMessage("Lumi are nevoie de acces la notificări. Activează acum?")
                .setPositiveButton("Activează") { _, _ -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                .setNegativeButton("Mai târziu", null).show()
        }
    }

    private fun checkAccessibilityService() {
        if (isAccessibilityServiceEnabled()) {
            // Service was just granted — allow the dialog to re-appear next launch if revoked
            accessibilityDialogShown = false
            return
        }
        if (accessibilityDialogShown) return  // already prompted this session
        accessibilityDialogShown = true
        AlertDialog.Builder(this)
            .setTitle("Serviciu Accesibilitate necesar")
            .setMessage(
                "Lumi are nevoie de Serviciul de Accesibilitate pentru a trimite mesaje " +
                "WhatsApp, seta timere \u0219i salva noti\u021Be complet autonom.\n\n" +
                "Pa\u0219i pe Samsung (Android 13+):\n" +
                "1. \u021Aine ap\u0103sat pe iconi\u021Ba Lumi \u2192 Informa\u021Bii aplica\u021Bie \u2192 " +
                "Permite set\u0103rile restric\u021Bionate\n" +
                "2. Deschide Set\u0103ri \u2192 Accesibilitate \u2192 Aplica\u021Bii instalate \u2192 Lumi \u2192 Activeaz\u0103"
            )
            .setPositiveButton("Deschide Accesibilitate") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Mai târziu", null)
            .show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = android.content.ComponentName(
            this, com.lumi.app.whatsapp.LumiAccessibilityService::class.java
        ).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.trim().equals(serviceName, ignoreCase = true) }
    }

    private fun checkManageExternalStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                if (accessibilityDialogShown) return // don't stack dialogs
                accessibilityDialogShown = true
                AlertDialog.Builder(this)
                    .setTitle("Acces fisiere necesar")
                    .setMessage("Lumi are nevoie de acces la fisiere (MANAGE_EXTERNAL_STORAGE) pentru a putea citi documentele tale (.pdf, .docx).")
                    .setPositiveButton("Setari") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.addCategory("android.intent.category.DEFAULT")
                            intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                            startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent()
                            intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                            startActivity(intent)
                        }
                    }
                    .setNegativeButton("Mai tarziu", null)
                    .show()
            }
        }
    }

    private fun updateNotificationBadge() {
        val enabled = LumiNotificationService.isEnabled(this)
        binding.tvNotifStatus.text = if (enabled) "Notificari ON" else "Notificari OFF"
        binding.tvNotifStatus.background = ContextCompat.getDrawable(this, if (enabled) R.drawable.chip_ok else R.drawable.chip_default)
        binding.tvNotifStatus.setTextColor(ContextCompat.getColor(this, if (enabled) R.color.color_ok else R.color.text_hint))
    }

    // ─── Orb animation ───────────────────────────────────────────────────────

    private fun startOrbAnimation() {
        orbAnimator?.cancel()
        orbAnimator = ValueAnimator.ofFloat(1f, 1.12f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val s = anim.animatedValue as Float
                binding.voiceOrb.scaleX = s; binding.voiceOrb.scaleY = s
                binding.voiceRing1.scaleX = s; binding.voiceRing1.scaleY = s
            }
            start()
        }
    }

    private fun stopOrbAnimation() {
        orbAnimator?.cancel(); orbAnimator = null
        binding.voiceOrb.scaleX = 1f; binding.voiceOrb.scaleY = 1f
        binding.voiceRing1.scaleX = 1f; binding.voiceRing1.scaleY = 1f
    }

    // ─── Menu ─────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.main_menu, menu); return true }
    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.action_clear -> { viewModel.clearHistory(); true }
        else -> super.onOptionsItemSelected(item)
    }
}
