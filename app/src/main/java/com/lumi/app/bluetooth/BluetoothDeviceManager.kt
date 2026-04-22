package com.lumi.app.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

data class BtDeviceInfo(
    val name: String,
    val address: String,
    val isConnected: Boolean
)

class BluetoothDeviceManager(private val context: Context) {

    private val btAdapter get() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isEnabled(): Boolean = btAdapter?.isEnabled == true

    fun getPairedDevices(): List<BtDeviceInfo> {
        return try {
            btAdapter?.bondedDevices?.map { d ->
                BtDeviceInfo(
                    name = d.name ?: d.address,
                    address = d.address,
                    isConnected = isDeviceConnected(d)
                )
            } ?: emptyList()
        } catch (_: SecurityException) { emptyList() }
    }

    fun getConnectedDevices(): List<BtDeviceInfo> = getPairedDevices().filter { it.isConnected }

    private fun isDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            device.javaClass.getMethod("isConnected").invoke(device) as? Boolean ?: false
        } catch (_: Exception) { false }
    }

    fun formatDeviceList(): String {
        if (!isEnabled()) return "Bluetooth este dezactivat."
        val paired = getPairedDevices()
        if (paired.isEmpty()) return "Nu exista dispozitive Bluetooth asociate."
        val sb = StringBuilder()
        val connected = paired.filter { it.isConnected }
        val disconnected = paired.filter { !it.isConnected }
        if (connected.isNotEmpty()) {
            sb.appendLine("Conectate:")
            connected.forEach { sb.appendLine("  - ${it.name}") }
        }
        if (disconnected.isNotEmpty()) {
            sb.appendLine("Asociate (neconectate):")
            disconnected.forEach { sb.appendLine("  - ${it.name}") }
        }
        return sb.toString().trim()
    }

    fun findDevice(name: String): BtDeviceInfo? {
        val q = name.lowercase()
        return getPairedDevices().firstOrNull { it.name.lowercase().contains(q) }
    }

    /**
     * Regular apps cannot programmatically connect Classic BT profiles (requires BLUETOOTH_PRIVILEGED).
     * Opens Bluetooth settings so the user can connect manually.
     */
    fun guideConnect(deviceName: String, lumiDeviceAddress: String): String {
        val device = findDevice(deviceName)
            ?: return "Dispozitivul \"$deviceName\" nu a fost gasit in lista celor asociate. Verifica ca este pornit si asociat."
        if (device.address.equals(lumiDeviceAddress, ignoreCase = true)) {
            return "You can't cut down a tree with a stick from itself."
        }
        if (device.isConnected) return "${device.name} este deja conectat."
        openBluetoothSettings()
        return "Setarile Bluetooth s-au deschis. Apasa pe ${device.name} pentru a te conecta."
    }

    fun guideDisconnect(deviceName: String, lumiDeviceAddress: String): String {
        val device = findDevice(deviceName)
            ?: return "Dispozitivul \"$deviceName\" nu a fost gasit."
        if (device.address.equals(lumiDeviceAddress, ignoreCase = true)) {
            return "You can't cut down a tree with a stick from itself."
        }
        if (!device.isConnected) return "${device.name} nu este conectat momentan."
        openBluetoothSettings()
        return "Setarile Bluetooth s-au deschis. Apasa pe ${device.name} pentru a te deconecta."
    }

    fun guidePair(deviceName: String): String {
        openBluetoothSettings()
        return "Setarile Bluetooth s-au deschis. Activeaza \"$deviceName\" si apasa pe el pentru asociere."
    }

    private fun openBluetoothSettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (_: Exception) {}
    }
}
