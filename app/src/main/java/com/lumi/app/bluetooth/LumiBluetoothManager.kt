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
import android.util.Log
import java.util.UUID

/**
 * BLE protocol with the Lumi hardware device.
 *
 * Service UUID:   LUMI_SERVICE_UUID
 * Characteristics:
 *   AUDIO_CHAR   — NOTIFY — raw PCM audio frames from the device mic
 *   IMAGE_CHAR   — NOTIFY — JPEG image chunks (reassembled here)
 *   CMD_CHAR     — WRITE  — send commands back to device (e.g., play TTS audio)
 *   STATUS_CHAR  — READ   — device status byte
 *
 * Image chunk protocol:
 *   byte[0]   = 0x00..0xFE  → chunk index
 *   byte[0]   = 0xFF        → last chunk; payload is total byte count (4 bytes LE)
 *   byte[1..] = JPEG data
 *
 * Audio chunk protocol:
 *   byte[0..1] = sequence number (big-endian uint16)
 *   byte[2..]  = PCM s16le 16kHz mono samples
 */
class LumiBluetoothManager(private val context: Context) {

    companion object {
        private const val TAG = "LumiBT"
        val LUMI_SERVICE_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val AUDIO_CHAR_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789ab1")
        val IMAGE_CHAR_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789ab2")
        val CMD_CHAR_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789ab3")
        val STATUS_CHAR_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789ab4")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val TTS_TEXT_CHAR_UUID: UUID    = UUID.fromString("12345678-1234-1234-1234-123456789ab5")
        val DEVICE_INFO_CHAR_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789ab6")
        val SPEECH_TEXT_CHAR_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789ab7")

        const val CMD_SPEAK           = 0x01.toByte()
        const val CMD_STOP            = 0x02.toByte()
        const val CMD_READY           = 0x03.toByte()
        const val CMD_SETUP_FP        = 0x04.toByte()
        const val CMD_FINGERPRINT_ON  = 0x05.toByte()
        const val CMD_FINGERPRINT_OFF = 0x06.toByte()
    }

    interface Listener {
        fun onConnectionStateChanged(state: ConnectionState)
        fun onAudioFrame(pcmData: ByteArray, sequenceNum: Int)
        fun onImageReceived(jpegData: ByteArray)
        fun onError(message: String)
        fun onSpeechText(text: String) {}
        fun onDeviceInfo(deviceId: String, colorTheme: String, fingerprintEnabled: Boolean) {}
    }

    enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter: BluetoothAdapter? get() = btManager.adapter

    private var gatt: BluetoothGatt? = null
    private var listener: Listener? = null
    private var imageBuffer = mutableListOf<Pair<Int, ByteArray>>() // chunkIndex → data
    private var imageTotalSize = 0

    var connectionState = ConnectionState.DISCONNECTED
        private set(value) {
            field = value
            listener?.onConnectionStateChanged(value)
        }

    fun setListener(l: Listener) { listener = l }

    fun isBluetoothEnabled() = adapter?.isEnabled == true

    // ─── Scanning ────────────────────────────────────────────────────────────

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
            listener?.onError("Nu s-a putut conecta la adresa BT: $address")
        }
    }

    // ─── GATT Connection ────────────────────────────────────────────────────

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
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected, discovering services…")
                    try { gatt.discoverServices() } catch (e: SecurityException) { }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected")
                    connectionState = ConnectionState.DISCONNECTED
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener?.onError("Service discovery eșuată: $status")
                return
            }
            val service = gatt.getService(LUMI_SERVICE_UUID)
            if (service == null) {
                listener?.onError("Serviciul Lumi nu a fost găsit pe dispozitiv.")
                return
            }
            enableNotify(gatt, service, AUDIO_CHAR_UUID)
            enableNotify(gatt, service, IMAGE_CHAR_UUID)
            enableNotify(gatt, service, SPEECH_TEXT_CHAR_UUID)

            // Read device info (device_id, color_theme, fingerprint_enabled)
            service.getCharacteristic(DEVICE_INFO_CHAR_UUID)?.let { info ->
                try { gatt.readCharacteristic(info) } catch (e: SecurityException) { }
            }

            // Request higher MTU so TTS text fits in one write
            try { gatt.requestMtu(512) } catch (e: SecurityException) { }

            // Signal device we're ready
            service.getCharacteristic(CMD_CHAR_UUID)?.let { cmd ->
                cmd.value = byteArrayOf(CMD_READY)
                try { gatt.writeCharacteristic(cmd) } catch (e: SecurityException) { }
            }
            connectionState = ConnectionState.CONNECTED
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && char.uuid == DEVICE_INFO_CHAR_UUID) {
                parseDeviceInfo(char.value ?: return)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && char.uuid == DEVICE_INFO_CHAR_UUID) {
                parseDeviceInfo(value)
            }
        }

        @Deprecated("Used for API < 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            handleCharacteristicChange(char.uuid, char.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChange(char.uuid, value)
        }
    }

    private fun enableNotify(gatt: BluetoothGatt, service: BluetoothGattService, charUuid: UUID) {
        val char = service.getCharacteristic(charUuid) ?: return
        try {
            gatt.setCharacteristicNotification(char, true)
            val desc = char.getDescriptor(CCCD_UUID)
            desc?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            desc?.let { gatt.writeDescriptor(it) }
        } catch (e: SecurityException) {
            Log.e(TAG, "enableNotify SecurityException", e)
        }
    }

    private fun handleCharacteristicChange(uuid: UUID, data: ByteArray) {
        when (uuid) {
            AUDIO_CHAR_UUID -> {
                if (data.size < 2) return
                val seq = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                listener?.onAudioFrame(data.copyOfRange(2, data.size), seq)
            }
            IMAGE_CHAR_UUID -> handleImageChunk(data)
            SPEECH_TEXT_CHAR_UUID -> {
                val text = data.toString(Charsets.UTF_8).trim()
                if (text.isNotEmpty()) listener?.onSpeechText(text)
            }
        }
    }

    private fun parseDeviceInfo(data: ByteArray) {
        try {
            val json = org.json.JSONObject(data.toString(Charsets.UTF_8))
            val deviceId = json.optString("device_id", "")
            val theme = json.optString("color_theme", "grey")
            val fp = json.optBoolean("fingerprint_enabled", false)
            listener?.onDeviceInfo(deviceId, theme, fp)
        } catch (_: Exception) {}
    }

    private fun handleImageChunk(data: ByteArray) {
        if (data.isEmpty()) return
        val chunkIndex = data[0].toInt() and 0xFF
        if (chunkIndex == 0xFF) {
            // Last chunk marker — contains total size in next 4 bytes (informational)
            val assembled = imageBuffer
                .sortedBy { it.first }
                .flatMap { it.second.toList() }
                .toByteArray()
            imageBuffer.clear()
            if (assembled.isNotEmpty()) listener?.onImageReceived(assembled)
        } else {
            imageBuffer.add(Pair(chunkIndex, data.copyOfRange(1, data.size)))
        }
    }

    // ─── Commands ────────────────────────────────────────────────────────────

    fun sendCommand(cmd: Byte) {
        val service = gatt?.getService(LUMI_SERVICE_UUID) ?: return
        val char = service.getCharacteristic(CMD_CHAR_UUID) ?: return
        char.value = byteArrayOf(cmd)
        try { gatt?.writeCharacteristic(char) } catch (e: SecurityException) { }
    }

    /** Send TTS text to the device to be spoken via espeak-ng.
     *  Chunks at 240 bytes (safe for CYW43438 BLE on Pi Zero 2W).
     *  Each chunk: byte[0]=0x00 (continue) or 0x01 (last), bytes[1..]=UTF-8 text.
     */
    fun sendTtsText(text: String) {
        val service = gatt?.getService(LUMI_SERVICE_UUID) ?: return
        val char = service.getCharacteristic(TTS_TEXT_CHAR_UUID) ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        val maxPayload = 239 // 240 - 1 flag byte
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + maxPayload, bytes.size)
            val isLast = end == bytes.size
            val chunk = ByteArray(1 + (end - offset))
            chunk[0] = if (isLast) 0x01 else 0x00
            bytes.copyInto(chunk, destinationOffset = 1, startIndex = offset, endIndex = end)
            char.value = chunk
            try {
                gatt?.writeCharacteristic(char)
                if (!isLast) Thread.sleep(30)
            } catch (e: SecurityException) { return }
            offset = end
        }
    }

    fun disconnect() {
        stopScan()
        try { gatt?.disconnect(); gatt?.close() } catch (e: SecurityException) { }
        gatt = null
        connectionState = ConnectionState.DISCONNECTED
    }
}
