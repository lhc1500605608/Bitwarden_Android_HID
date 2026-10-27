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
        data class Connecting(val address: String) : State
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
    private var selectedPeer: BluetoothDevice? = null
    private var selectedPeerAddress: String? = null
    @Volatile
    private var isRegistered = false
    /** Non-sensitive control event name only, for debugging host HID compatibility. */
    @Volatile
    var lastHostControlRequest: String = "none"
        private set

    @Volatile
    var lastProfileTransition: String = "none"
        private set

    private val executor = Executor { task -> mainHandler.post(task) }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            isRegistered = registered
            if (!registered) {
                peer = null
                state = State.Idle
                return
            }
            // A host may initiate a connection during pairing or while the HID app registers.
            // A virtual cable alone does not authorize sending. Only a previously selected
            // host may become the active destination.
            val selected = selectedPeer
            state = if (selected != null &&
                hid?.getConnectionState(selected) == BluetoothProfile.STATE_CONNECTED
            ) {
                peer = selected
                State.Connected(selected.address)
            } else {
                State.Ready
            }
        }

        override fun onGetReport(
            device: BluetoothDevice,
            type: Byte,
            id: Byte,
            bufferSize: Int,
        ) {
            lastHostControlRequest = "GET_REPORT(type=${type.toInt()}, id=${id.toInt()}, size=$bufferSize)"
            val service = hid ?: return
            if (!hasPermission()) return
            if (id.toInt() != REPORT_ID) {
                service.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
                return
            }
            when (type) {
                // Never reflect real keys here. Reply with all keys released.
                BluetoothHidDevice.REPORT_TYPE_INPUT ->
                    service.replyReport(device, type, id, ByteArray(KEYBOARD_INPUT_BYTES))
                // Standard keyboard LED output is exactly one byte.
                BluetoothHidDevice.REPORT_TYPE_OUTPUT ->
                    service.replyReport(device, type, id, ByteArray(LED_OUTPUT_BYTES))
                else ->
                    service.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
            }
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            lastHostControlRequest = "SET_PROTOCOL(${protocol.toInt()})"
            // Boot and Report protocols both use the same eight-byte keyboard input.
        }

        override fun onSetReport(
            device: BluetoothDevice,
            type: Byte,
            id: Byte,
            data: ByteArray,
        ) {
            lastHostControlRequest =
                "SET_REPORT(type=${type.toInt()}, id=${id.toInt()}, size=${data.size})"
            if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT &&
                id.toInt() == REPORT_ID && data.size == LED_OUTPUT_BYTES
            ) {
                // Num/Caps/Scroll lock LED state from the host; no keystrokes and
                // no credentials. Valid SET_REPORT is acknowledged by the stack.
                return
            }
            if (hasPermission()) {
                hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
            }
        }

        override fun onInterruptData(
            device: BluetoothDevice,
            reportId: Byte,
            data: ByteArray,
        ) {
            // HID keyboard LED state may arrive on the interrupt OUT channel.
            // Never record its payload; metadata is sufficient for diagnosis.
            lastHostControlRequest =
                "INTERRUPT_OUT(id=${reportId.toInt()}, size=${data.size})"
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            lastHostControlRequest = "VIRTUAL_CABLE_UNPLUG"
            lastProfileTransition = "VirtualCableUnplug"
            if (device.address == selectedPeerAddress) {
                peer = null
                state = State.Error("Host removed the HID virtual cable; re-pair may be required")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, newState: Int) {
            lastProfileTransition = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                else -> "UNKNOWN($newState)"
            }
            // Unsolicited host connections must not become approved input destinations.
            // The user can explicitly select an already-connected bonded host later.
            if (device.address != selectedPeerAddress) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTING -> {
                    state = State.Connecting(device.address)
                }
                BluetoothProfile.STATE_CONNECTED -> {
                    peer = device
                    state = State.Connected(device.address)
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    peer = null
                    state = State.Connecting(device.address)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    peer = null
                    state = State.Error("Selected host disconnected or connection failed")
                }
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
                isRegistered = false
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

    /**
     * Selecting a bonded host is an explicit user action. This also adopts a HID connection
     * the computer established first, which otherwise may never produce another CONNECTED
     * callback when the user taps Connect.
     */
    fun connect(device: BluetoothDevice): Boolean {
        if (!hasPermission() || !isRegistered ||
            device.bondState != BluetoothDevice.BOND_BONDED
        ) return false
        val service = hid ?: return false
        if (peer != null && peer?.address != device.address) {
            state = State.Error("Disconnect the previous host before selecting another")
            return false
        }
        selectedPeer = device
        selectedPeerAddress = device.address
        return when (service.getConnectionState(device)) {
            BluetoothProfile.STATE_CONNECTED -> {
                peer = device
                state = State.Connected(device.address)
                true
            }
            BluetoothProfile.STATE_CONNECTING -> {
                state = State.Connecting(device.address)
                true
            }
            BluetoothProfile.STATE_DISCONNECTING -> {
                state = State.Error("Host is disconnecting; try again after it disconnects")
                false
            }
            else -> {
                state = State.Connecting(device.address)
                val accepted = service.connect(device)
                if (!accepted) {
                    // Connection can win a race with our query; check the actual profile
                    // before calling the request a failure.
                    if (service.getConnectionState(device) ==
                        BluetoothProfile.STATE_CONNECTED
                    ) {
                        peer = device
                        state = State.Connected(device.address)
                        true
                    } else {
                        state = State.Error("HID connect command rejected by Bluetooth stack")
                        false
                    }
                } else {
                    true
                }
            }
        }
    }

    /** Reports only profile/control metadata, never keystrokes or secrets. */
    fun diagnosticSummary(): String {
        val connection = selectedPeer?.let { device ->
            if (hasPermission()) {
                when (hid?.getConnectionState(device)) {
                    BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                    BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                    BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                    BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                    else -> "UNAVAILABLE"
                }
            } else {
                "NO_PERMISSION"
            }
        } ?: "NO_HOST_SELECTED"
        return "registered=$isRegistered, HID=$connection, " +
            "lastTransition=$lastProfileTransition, lastHostRequest=$lastHostControlRequest"
    }

    /** Refresh the actual Bluetooth HID profile state without connecting a new host. */
    fun refreshConnectionState(): State {
        if (!hasPermission() || !isRegistered) return state
        val device = selectedPeer ?: return state
        val service = hid ?: return state
        state = when (service.getConnectionState(device)) {
            BluetoothProfile.STATE_CONNECTED -> {
                peer = device
                State.Connected(device.address)
            }
            BluetoothProfile.STATE_CONNECTING -> State.Connecting(device.address)
            BluetoothProfile.STATE_DISCONNECTING -> {
                peer = null
                State.Error("Host is disconnecting")
            }
            else -> {
                peer = null
                State.Error("Bluetooth HID profile is disconnected (pairing alone is not HID)")
            }
        }
        return state
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
        selectedPeer = null
        selectedPeerAddress = null
        isRegistered = false
        hid = null
        state = State.Idle
    }

    companion object {
        // This descriptor does NOT declare a Report ID (0x85). Android's sendReport()
        // therefore requires ID 0 and EXACTLY eight report bytes, with no ID prefix.
        // This is a single, boot-style keyboard HID report (not a mouse or combo).
        private const val REPORT_ID = 0
        private const val KEYBOARD_INPUT_BYTES = 8
        private const val LED_OUTPUT_BYTES = 1

        // Standard boot-style keyboard with one-byte LED output report.
        // 8-byte IN = modifier + reserved + 6 keys.
        // 1-byte OUT = five lock LEDs + three padding bits.
        // No Report ID tag: use id=0 with Android's HID APIs.
        private val REPORT_DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01,
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00,
            0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x03,
            // Output: NumLock, CapsLock, ScrollLock, Compose, Kana
            0x95.toByte(), 0x05, 0x75, 0x01, 0x05, 0x08,
            0x19, 0x01, 0x29, 0x05, 0x91.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x03, 0x91.toByte(), 0x03,
            // Six simultaneous key usages, each 8 bits.
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
