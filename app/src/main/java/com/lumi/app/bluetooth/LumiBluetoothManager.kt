package com.lumi.app.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * BLE GATT client for the Lumi hardware device.
 *
 * Characteristics (all under LUMI_SERVICE_UUID):
 *   AUDIO_CHAR        — NOTIFY — raw PCM audio frames (live mic stream)
 *   IMAGE_CHAR        — NOTIFY — JPEG image chunks
 *   AUDIO_RECORD_CHAR — NOTIFY — recorded WAV chunks (hold-mode sound capture)
 *   CMD_CHAR          — WRITE  — commands to device
 *   STATUS_CHAR       — READ   — device status byte
 *   TTS_TEXT_CHAR     — WRITE  — text for device to speak via espeak-ng
 *   DEVICE_INFO_CHAR  — READ   — JSON: device_id, color_theme, fingerprint_enabled
 *   SPEECH_TEXT_CHAR  — NOTIFY — recognised speech text from device
 *
 * Chunked-data protocol (images, recorded audio):
 *   byte[0]   = 0x00..0xFE → chunk index; byte[1..] = data
 *   byte[0]   = 0xFF       → last chunk; byte[1..4] = total size (LE); byte[5..] = tail data
 *
 * TTS text protocol (phone → device, WRITE_TYPE_NO_RESPONSE):
 *   byte[0] = 0x00 → more chunks follow
 *   byte[0] = 0x01 → last (or only) chunk — device speaks now
 *   byte[1..] = UTF-8 text payload
 *
 * Connection setup (sequential, each gated by the previous GATT callback):
 *   onServicesDiscovered → requestMtu(512)
 *   onMtuChanged         → enableNotify×4 (600 ms each) → readDeviceInfo → sendCmdReady → CONNECTED
 */
class LumiBluetoothManager(private val context: Context) {

    companion object {
        private const val TAG = "LumiBT"
        val LUMI_SERVICE_UUID: UUID      = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val AUDIO_CHAR_UUID: UUID        = UUID.fromString("12345678-1234-1234-1234-123456789ab1")
        val IMAGE_CHAR_UUID: UUID        = UUID.fromString("12345678-1234-1234-1234-123456789ab2")
        val CMD_CHAR_UUID: UUID          = UUID.fromString("12345678-1234-1234-1234-123456789ab3")
        val STATUS_CHAR_UUID: UUID       = UUID.fromString("12345678-1234-1234-1234-123456789ab4")
        val TTS_TEXT_CHAR_UUID: UUID     = UUID.fromString("12345678-1234-1234-1234-123456789ab5")
        val DEVICE_INFO_CHAR_UUID: UUID  = UUID.fromString("12345678-1234-1234-1234-123456789ab6")
        val SPEECH_TEXT_CHAR_UUID: UUID  = UUID.fromString("12345678-1234-1234-1234-123456789ab7")
        val AUDIO_RECORD_CHAR_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789ab8")
        val CCCD_UUID: UUID              = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val CMD_SPEAK           = 0x01.toByte()
        const val CMD_STOP            = 0x02.toByte()
        const val CMD_READY           = 0x03.toByte()
        const val CMD_SETUP_FP        = 0x04.toByte()
        const val CMD_FINGERPRINT_ON  = 0x05.toByte()
        const val CMD_FINGERPRINT_OFF = 0x06.toByte()

        private const val NOTIFY_SETUP_DELAY_MS = 600L
    }

    interface Listener {
        fun onConnectionStateChanged(state: ConnectionState)
        fun onAudioFrame(pcmData: ByteArray, sequenceNum: Int)
        fun onImageReceived(jpegData: ByteArray)
        fun onError(message: String)
        fun onSpeechText(text: String) {}
        fun onDeviceInfo(deviceId: String, colorTheme: String, fingerprintEnabled: Boolean) {}
        fun onAudioRecordingReceived(wavBytes: ByteArray) {}
    }

    enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter: BluetoothAdapter? get() = btManager.adapter

    private var gatt: BluetoothGatt? = null
    private var listener: Listener? = null
    private val handler = Handler(Looper.getMainLooper())

    private val imageBuffer       = mutableListOf<Pair<Int, ByteArray>>()
    private val audioRecordBuffer = mutableListOf<Pair<Int, ByteArray>>()

    private var pendingService: BluetoothGattService? = null
    @Volatile private var negotiatedMtu = 23

    var connectionState = ConnectionState.DISCONNECTED
        private set(value) {
            field = value
            listener?.onConnectionStateChanged(value)
        }

    fun setListener(l: Listener) { listener = l }
    fun isBluetoothEnabled() = adapter?.isEnabled == true

    // ─── Scanning ─────────────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try { device.name } catch (e: SecurityException) { null }
            } else { device.name }
            if (name?.startsWith("Lumi") == true) {
                stopScan()
                connect(device)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            listener?.onError("BLE scan failed: $errorCode")
            connectionState = ConnectionState.DISCONNECTED
        }
    }

    fun startScan() {
        connectionState = ConnectionState.SCANNING
        try {
            adapter?.bluetoothLeScanner?.startScan(scanCallback)
        } catch (e: SecurityException) {
            listener?.onError("Permisiune Bluetooth lipsă.")
            connectionState = ConnectionState.DISCONNECTED
        }
    }

    fun stopScan() {
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
    }

    fun connectToAddress(address: String) {
        try {
            val device = adapter?.getRemoteDevice(address) ?: return
            connect(device)
        } catch (e: Exception) {
            listener?.onError("Nu s-a putut conecta: $address")
        }
    }

    // ─── GATT Connection ──────────────────────────────────────────────────────

    private fun connect(device: BluetoothDevice) {
        connectionState = ConnectionState.CONNECTING
        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: SecurityException) {
            listener?.onError("Permisiune BLUETOOTH_CONNECT lipsă.")
            connectionState = ConnectionState.DISCONNECTED
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT error status=$status — resetting")
                clearState()
                try { gatt.close() } catch (_: SecurityException) {}
                this@LumiBluetoothManager.gatt = null
                connectionState = ConnectionState.DISCONNECTED
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected, discovering services…")
                    try { gatt.discoverServices() } catch (_: SecurityException) {}
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected")
                    clearState()
                    connectionState = ConnectionState.DISCONNECTED
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener?.onError("Service discovery eșuată: $status"); return
            }
            val service = gatt.getService(LUMI_SERVICE_UUID) ?: run {
                listener?.onError("Serviciul Lumi nu a fost găsit."); return
            }
            pendingService = service
            try { gatt.requestMtu(512) } catch (_: SecurityException) {
                onMtuChanged(gatt, 23, BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            Log.i(TAG, "MTU: $negotiatedMtu")
            val service = pendingService ?: return
            var t = 0L
            fun post(ms: Long, block: () -> Unit) { handler.postDelayed(block, ms.also { t += it }) }

            post(0L)                    { enableNotify(gatt, service, AUDIO_CHAR_UUID) }
            post(NOTIFY_SETUP_DELAY_MS) { enableNotify(gatt, service, IMAGE_CHAR_UUID) }
            post(NOTIFY_SETUP_DELAY_MS) { enableNotify(gatt, service, SPEECH_TEXT_CHAR_UUID) }
            post(NOTIFY_SETUP_DELAY_MS) { enableNotify(gatt, service, AUDIO_RECORD_CHAR_UUID) }
            post(NOTIFY_SETUP_DELAY_MS) {
                service.getCharacteristic(DEVICE_INFO_CHAR_UUID)?.let { info ->
                    try { gatt.readCharacteristic(info) } catch (_: SecurityException) {}
                }
            }
            post(NOTIFY_SETUP_DELAY_MS) {
                service.getCharacteristic(CMD_CHAR_UUID)?.let { cmd ->
                    cmd.value = byteArrayOf(CMD_READY)
                    try { gatt.writeCharacteristic(cmd) } catch (_: SecurityException) {}
                }
                connectionState = ConnectionState.CONNECTED
            }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicRead(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && char.uuid == DEVICE_INFO_CHAR_UUID)
                parseDeviceInfo(char.value ?: return)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && char.uuid == DEVICE_INFO_CHAR_UUID)
                parseDeviceInfo(value)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            handleCharacteristicChange(char.uuid, char.value ?: return)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray) {
            handleCharacteristicChange(char.uuid, value)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "CCCD write ${descriptor.characteristic.uuid}: $status")
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun enableNotify(gatt: BluetoothGatt, service: BluetoothGattService, charUuid: UUID) {
        val char = service.getCharacteristic(charUuid) ?: return
        try {
            gatt.setCharacteristicNotification(char, true)
            char.getDescriptor(CCCD_UUID)?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "enableNotify $charUuid", e)
        }
    }

    private fun handleCharacteristicChange(uuid: UUID, data: ByteArray) {
        when (uuid) {
            AUDIO_CHAR_UUID -> {
                if (data.size < 2) return
                val seq = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                listener?.onAudioFrame(data.copyOfRange(2, data.size), seq)
            }
            IMAGE_CHAR_UUID        -> handleChunkedData(data, imageBuffer) { listener?.onImageReceived(it) }
            AUDIO_RECORD_CHAR_UUID -> handleChunkedData(data, audioRecordBuffer) { listener?.onAudioRecordingReceived(it) }
            SPEECH_TEXT_CHAR_UUID  -> {
                val text = data.toString(Charsets.UTF_8).trim()
                if (text.isNotEmpty()) listener?.onSpeechText(text)
            }
        }
    }

    @Synchronized
    private fun handleChunkedData(
        data: ByteArray,
        buffer: MutableList<Pair<Int, ByteArray>>,
        onComplete: (ByteArray) -> Unit
    ) {
        if (data.isEmpty()) return
        val chunkIndex = data[0].toInt() and 0xFF
        if (chunkIndex == 0xFF) {
            val assembled = buffer.sortedBy { it.first }.flatMap { it.second.toList() }.toByteArray()
            buffer.clear()
            if (assembled.isNotEmpty()) onComplete(assembled)
        } else {
            buffer.add(Pair(chunkIndex, data.copyOfRange(1, data.size)))
        }
    }

    private fun parseDeviceInfo(data: ByteArray) {
        try {
            val json = org.json.JSONObject(data.toString(Charsets.UTF_8))
            listener?.onDeviceInfo(
                json.optString("device_id", ""),
                json.optString("color_theme", "grey"),
                json.optBoolean("fingerprint_enabled", false)
            )
        } catch (_: Exception) { Log.w(TAG, "Failed to parse device info") }
    }

    private fun clearState() {
        handler.removeCallbacksAndMessages(null)
        synchronized(imageBuffer)       { imageBuffer.clear() }
        synchronized(audioRecordBuffer) { audioRecordBuffer.clear() }
        pendingService = null
        negotiatedMtu = 23
    }

    // ─── Commands ─────────────────────────────────────────────────────────────

    fun sendCommand(cmd: Byte) {
        val g = gatt ?: return
        val service = g.getService(LUMI_SERVICE_UUID) ?: return
        val char = service.getCharacteristic(CMD_CHAR_UUID) ?: return
        char.value = byteArrayOf(cmd)
        try { g.writeCharacteristic(char) } catch (_: SecurityException) {}
    }

    fun sendTtsText(text: String) {
        val g = gatt ?: return
        val service = g.getService(LUMI_SERVICE_UUID) ?: return
        val char = service.getCharacteristic(TTS_TEXT_CHAR_UUID) ?: return
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        val bytes = text.toByteArray(Charsets.UTF_8)
        val maxPayload = (negotiatedMtu - 4).coerceAtLeast(20)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + maxPayload, bytes.size)
            val chunk = ByteArray(1 + (end - offset))
            chunk[0] = if (end == bytes.size) 0x01 else 0x00
            bytes.copyInto(chunk, destinationOffset = 1, startIndex = offset, endIndex = end)
            char.value = chunk
            try { g.writeCharacteristic(char) } catch (_: SecurityException) { return }
            offset = end
        }
    }

    fun disconnect() {
        stopScan()
        clearState()
        try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) {}
        gatt = null
        connectionState = ConnectionState.DISCONNECTED
    }
}
