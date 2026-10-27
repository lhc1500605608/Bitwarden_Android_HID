package com.x8bit.bitwarden.data.platform.manager.hid

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/**
 * Experimental, explicit-user-action-only Bluetooth HID keyboard transport.
 *
 * No vault or clipboard access happens here. The caller must check the vault is unlocked,
 * confirm the selected peer and obtain separate confirmation before supplying any secrets.
 * Only US-layout printable ASCII is supported; unsupported characters are rejected up front.
 *
 * This class is intentionally NOT wired to production vault actions in the first milestone.
 */
@RequiresApi(Build.VERSION_CODES.S)
class BluetoothHidKeyboard(private val context: Context) {
    sealed interface State {
        data object Idle : State
        data object Registering : State
        data object Ready : State
        data class Connected(val address: String) : State
        data class Error(val reason: String) : State
    }

    @Volatile
    var state: State = State.Idle
        private set

    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var hid: BluetoothHidDevice? = null
    private var peer: BluetoothDevice? = null
    private val executor = Executor { task -> task.run() }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            state = if (registered) State.Ready else State.Idle
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                peer = device
                state = State.Connected(device.address)
            } else if (peer?.address == device.address) {
                peer = null
                state = State.Ready
            }
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            if (!hasPermission()) {
                state = State.Error("Bluetooth permission missing")
                return
            }
            val service = proxy as BluetoothHidDevice
            hid = service
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "VaultKey Test Keyboard",
                "Experimental HID keyboard (no clipboard)",
                "VaultKey",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                REPORT_DESCRIPTOR,
            )
            if (!service.registerApp(sdp, null as BluetoothHidDeviceAppQosSettings?,
                    null as BluetoothHidDeviceAppQosSettings?, executor, callback)) {
                state = State.Error("HID registration failed")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hid = null
                peer = null
                state = State.Idle
            }
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Start after BLUETOOTH_CONNECT runtime permission has been granted. */
    fun start(): Boolean {
        if (!hasPermission()) {
            state = State.Error("Bluetooth permission missing")
            return false
        }
        state = State.Registering
        return adapter?.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
            ?: false.also { state = State.Error("Bluetooth adapter not available") }
    }

    /** Uses only already bonded devices. Never silently initiates pairing. */
    fun pairedDevices(): List<BluetoothDevice> =
        if (hasPermission()) adapter?.bondedDevices?.toList().orEmpty() else emptyList()

    fun connect(device: BluetoothDevice): Boolean {
        if (!hasPermission() || device.bondState != BluetoothDevice.BOND_BONDED) return false
        return hid?.connect(device) ?: false
    }

    /** Validates the WHOLE text before sending any key events. Call only on explicit user action. */
    fun sendAscii(text: String): Boolean {
        val service = hid ?: return false
        val destination = peer ?: return false
        if (!hasPermission() || state !is State.Connected) return false
        val strokes = UsKeyboardReport.encode(text) ?: return false
        for (stroke in strokes) {
            if (!service.sendReport(destination, REPORT_ID, stroke)) return false
            if (!service.sendReport(destination, REPORT_ID, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))) {
                return false
            }
        }
        return true
    }

    fun close() {
        if (hasPermission()) {
            hid?.unregisterApp()
            hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        }
        peer = null
        hid = null
        state = State.Idle
    }

    companion object {
        private const val REPORT_ID = 1

        // Keyboard input report: modifier, reserved, six key slots.
        private val REPORT_DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01,
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00,
            0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
            0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
            0xC0.toByte(),
        )
    }
}

/** Pure mapping: no Android or vault dependencies. Reports exclude the HID report ID. */
internal object UsKeyboardReport {
    fun encode(text: String): List<ByteArray>? {
        val result = ArrayList<ByteArray>(text.length)
        for (char in text) {
            val key = keycode(char) ?: return null
            result += byteArrayOf(key.second.toByte(), 0, key.first.toByte(), 0, 0, 0, 0, 0)
        }
        return result
    }

    private fun keycode(c: Char): Pair<Int, Int>? {
        if (c in 'a'..'z') return (0x04 + c.code - 'a'.code) to 0
        if (c in 'A'..'Z') return (0x04 + c.code - 'A'.code) to 0x02
        if (c in '1'..'9') return (0x1E + c.code - '1'.code) to 0
        if (c == '0') return 0x27 to 0
        val symbols = mapOf(
            '\n' to (0x28 to 0), '\t' to (0x2B to 0), ' ' to (0x2C to 0),
            '-' to (0x2D to 0), '_' to (0x2D to 2), '=' to (0x2E to 0),
            '+' to (0x2E to 2), '[' to (0x2F to 0), '{' to (0x2F to 2),
            ']' to (0x30 to 0), '}' to (0x30 to 2), '\\' to (0x31 to 0),
            '|' to (0x31 to 2), ';' to (0x33 to 0), ':' to (0x33 to 2),
            '\'' to (0x34 to 0), '"' to (0x34 to 2), '`' to (0x35 to 0),
            '~' to (0x35 to 2), ',' to (0x36 to 0), '<' to (0x36 to 2),
            '.' to (0x37 to 0), '>' to (0x37 to 2), '/' to (0x38 to 0),
            '?' to (0x38 to 2), '!' to (0x1E to 2), '@' to (0x1F to 2),
            '#' to (0x20 to 2), '$' to (0x21 to 2), '%' to (0x22 to 2),
            '^' to (0x23 to 2), '&' to (0x24 to 2), '*' to (0x25 to 2),
            '(' to (0x26 to 2), ')' to (0x27 to 2),
        )
        return symbols[c]
    }
}
