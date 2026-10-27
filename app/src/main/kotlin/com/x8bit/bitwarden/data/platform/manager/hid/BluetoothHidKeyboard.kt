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
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

    /** Receive connection changes on the main thread. No secrets are ever passed to listeners. */
    @Volatile
    var onStateChanged: ((State) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sendMutex = Mutex()

    @Volatile
    var state: State = State.Idle
        private set(value) {
            field = value
            mainHandler.post { onStateChanged?.invoke(value) }
        }

    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var hid: BluetoothHidDevice? = null
    private var peer: BluetoothDevice? = null
    private var selectedPeerAddress: String? = null
    private val executor = Executor { task -> mainHandler.post(task) }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            state = if (!registered) State.Idle else peer?.let {
                State.Connected(it.address)
            } ?: State.Ready
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // An incoming HID connection is never authorization to transmit.
                if (device.address == selectedPeerAddress) {
                    peer = device
                    state = State.Connected(device.address)
                }
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
                "VaultKey Keyboard V2",
                "Keyboard-only HID descriptor; no mouse reports",
                "VaultKey",
                // Advertising a combo (keyboard + mouse) was inconsistent with our
                // keyboard-only HID descriptor. Hosts may parse input as mouse reports.
                BluetoothHidDevice.SUBCLASS1_KEYBOARD,
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
        if (state != State.Ready) return false
        val service = hid ?: return false
        selectedPeerAddress = device.address
        val requested = service.connect(device)
        if (!requested) selectedPeerAddress = null
        return requested
    }

    /**
     * Send only after the user chooses the destination and confirms the outgoing text.
     *
     * Validates the complete string BEFORE the first report. Key-down / key-up are paced
     * asynchronously so the UI stays responsive and hosts can detect each event.
     * A successful return means reports were accepted by Android, not that the remote
     * text field is guaranteed to contain the exact characters.
     */
    suspend fun sendAscii(text: String): Boolean = sendMutex.withLock {
        val strokes = UsKeyboardReport.encode(text) ?: return@withLock false
        val service = hid ?: return@withLock false
        val destination = peer ?: return@withLock false
        if (!hasPermission() || state !is State.Connected ||
            selectedPeerAddress != destination.address
        ) return@withLock false
        val release = ByteArray(8)
        withContext(Dispatchers.IO) {
            try {
                for (stroke in strokes) {
                    if (!hasPermission() || hid !== service ||
                        peer?.address != destination.address ||
                        state !is State.Connected
                    ) return@withContext false
                    if (!service.sendReport(destination, REPORT_ID, stroke)) {
                        return@withContext false
                    }
                    delay(50L)
                    if (!service.sendReport(destination, REPORT_ID, release)) {
                        return@withContext false
                    }
                    delay(50L)
                }
                true
            } finally {
                // Always attempt key release, including on cancellation.
                if (hasPermission()) {
                    runCatching { service.sendReport(destination, REPORT_ID, release) }
                }
            }
        }
    }

    fun close() {
        if (hasPermission()) {
            hid?.unregisterApp()
            hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        }
        peer = null
        selectedPeerAddress = null
        hid = null
        state = State.Idle
    }

    companion object {
        // This descriptor does NOT declare a Report ID (0x85). Android's sendReport()
        // therefore requires ID 0 and EXACTLY eight report bytes, with no ID prefix.
        // This is a single, boot-style keyboard HID report (not a mouse or combo).
        private const val REPORT_ID = 0

        // Keyboard input report: modifier, reserved, six key slots.
        private val REPORT_DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01,
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00,
            0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
            0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
            0xC0.toByte(),
        )
    }
}

/** Pure mapping: no Android or vault dependencies. Reports contain exactly 8 bytes. */
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
