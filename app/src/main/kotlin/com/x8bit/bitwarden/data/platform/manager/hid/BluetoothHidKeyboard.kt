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
import android.content.pm.ApplicationInfo
import android.util.Log
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
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
        data class Disconnecting(val address: String) : State
        data class Connected(val address: String) : State
        data class Error(val reason: String) : State
    }

    /** Receive connection changes on the main thread. No secrets are ever passed to listeners. */
    @Volatile
    var onStateChanged: ((State) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sendMutex = Mutex()
    // Any lock, background transition or HID link change invalidates the current send.
    // A generation counter prevents an immediate resume/reconnect from resuming old data.
    private val sendAbortGeneration = AtomicInteger(0)
    private val linkGeneration = AtomicInteger(0)
    @Volatile
    private var foregroundActive = false

    enum class SendStatus {
        REPORTS_ACCEPTED,
        INCOMPLETE_OR_UNCERTAIN,
        NOT_CONNECTED,
        NOT_FOREGROUND,
        UNSUPPORTED_TEXT,
    }

    data class SendResult(
        val status: SendStatus,
        val acceptedPresses: Int,
        val totalPresses: Int,
    ) {
        /** This is not confirmation the host typed every character. */
        val allReportsAccepted: Boolean get() = status == SendStatus.REPORTS_ACCEPTED
        fun summary(): String = when (status) {
            SendStatus.REPORTS_ACCEPTED ->
                "All $totalPresses key reports accepted by Android; verify the receiving host."
            SendStatus.INCOMPLETE_OR_UNCERTAIN ->
                "INCOMPLETE / UNKNOWN: $acceptedPresses of $totalPresses key presses " +
                    "accepted before interruption. Do not assume the host received them."
            SendStatus.NOT_CONNECTED ->
                "NOT SENT: Bluetooth HID is not fully CONNECTED."
            SendStatus.NOT_FOREGROUND ->
                "NOT SENT: phone locked, display off, or test Activity not foreground."
            SendStatus.UNSUPPORTED_TEXT ->
                "NOT SENT: character unsupported by US keyboard mapping."
        }
    }

    @Volatile
    var lastSendResult: SendResult? = null
        private set

    fun setForegroundActive(active: Boolean) {
        foregroundActive = active
        if (!active) abortActiveSend()
    }

    /** Cancel without retrying: keyboard HID cannot atomically roll back partial typing. */
    fun abortActiveSend() {
        sendAbortGeneration.incrementAndGet()
    }

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
    private var disconnectRequested = false
    // Track proxy acquisition and HID app registration separately. Calling start()
    // again while either is already active must not issue a second registerApp().
    private var proxyRequested = false
    private var registrationPending = false
    private var closed = false
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

    /** Debug-only structural diagnostics. No MAC addresses, user input or vault values. */
    private fun logEvent(event: String) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Log.i("VaultKeyHID", event)
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (closed) return
            registrationPending = false
            isRegistered = registered
            logEvent(if (registered) "event=registration_confirmed" else "event=registration_removed")
            if (!registered) {
                linkGeneration.incrementAndGet()
                abortActiveSend()
                peer = null
                disconnectRequested = false
                selectedPeer = null
                selectedPeerAddress = null
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
                linkGeneration.incrementAndGet()
                abortActiveSend()
                peer = null
                selectedPeer = null
                selectedPeerAddress = null
                disconnectRequested = false
                state = State.Error("Host removed the HID virtual cable; re-pair may be required")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, newState: Int) {
            lastProfileTransition = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                else -> "UNKNOWN"
            }
            logEvent("event=connection_state state=$lastProfileTransition")
            // Unsolicited host connections must not become approved input destinations.
            // The user can explicitly select an already-connected bonded host later.
            if (device.address != selectedPeerAddress) return
            linkGeneration.incrementAndGet()
            if (newState != BluetoothProfile.STATE_CONNECTED) abortActiveSend()
            when (newState) {
                BluetoothProfile.STATE_CONNECTING -> {
                    if (!disconnectRequested) state = State.Connecting(device.address)
                }
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!disconnectRequested) {
                        peer = device
                        state = State.Connected(device.address)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    peer = null
                    state = State.Disconnecting(device.address)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    peer = null
                    if (disconnectRequested) {
                        finishUserDisconnect()
                    } else {
                        val hostName = if (hasPermission()) {
                            runCatching { device.name }.getOrNull()
                        } else null
                        state = State.Error(
                            "HID disconnected or connection failed for " +
                                (hostName ?: device.address) +
                                ". Confirm this is a computer/tablet, not an audio device.",
                        )
                    }
                }
            }
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            if (closed || !proxyRequested) {
                // A late service callback must not resurrect a closed transport.
                runCatching { adapter?.closeProfileProxy(profile, proxy) }
                return
            }
            logEvent("event=proxy_connected")
            val service = proxy as BluetoothHidDevice
            hid = service
            if (!hasPermission()) {
                registrationPending = false
                state = State.Error("Bluetooth permission missing")
                return
            }
            requestRegistration(service)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                logEvent("event=proxy_disconnected")
                linkGeneration.incrementAndGet()
                abortActiveSend()
                hid = null
                peer = null
                selectedPeer = null
                selectedPeerAddress = null
                disconnectRequested = false
                isRegistered = false
                registrationPending = false
                proxyRequested = false
                if (!closed) state = State.Idle
            }
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Request registration exactly once until the Android registration callback arrives.
     * On immediate failure, close the stale profile proxy so Start can retry in-process.
     */
    private fun requestRegistration(service: BluetoothHidDevice): Boolean {
        if (closed) return false
        if (isRegistered || registrationPending) return true
        if (!hasPermission() || adapter?.isEnabled != true) {
            state = State.Error("Bluetooth is off or permission is missing")
            return false
        }
        registrationPending = true
        state = State.Registering
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "VaultKey Keyboard V2",
            "Keyboard-only HID descriptor; no mouse reports",
            "VaultKey",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            REPORT_DESCRIPTOR,
        )
        logEvent("event=registration_requested")
        val accepted = runCatching {
            service.registerApp(
                sdp,
                null as BluetoothHidDeviceAppQosSettings?,
                null as BluetoothHidDeviceAppQosSettings?,
                executor,
                callback,
            )
        }.getOrDefault(false)
        if (!accepted) {
            registrationPending = false
            isRegistered = false
            peer = null
            logEvent("event=registration_rejected")
            // Do not unregister here: a rejected request gives us no ownership of
            // the system-wide HID registration. Just drop our proxy and retry later.
            runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, service) }
            if (hid === service) hid = null
            proxyRequested = false
            state = State.Error("HID registration rejected. Check Bluetooth, then tap START to retry")
        }
        return accepted
    }

    /**
     * Idempotent startup:
     * - already registered: keep the current HID session;
     * - proxy/registration pending: do not request it again;
     * - callback says unregistered: register once using the existing proxy;
     * - no live proxy: obtain one.
     */
    fun start(): Boolean {
        if (closed) return false
        if (!hasPermission()) {
            state = State.Error("Bluetooth permission missing")
            return false
        }
        val bluetooth = adapter
        if (bluetooth?.isEnabled != true) {
            logEvent("event=start_bluetooth_off")
            state = State.Error("Bluetooth is off. Enable Bluetooth, then tap START")
            return false
        }
        if (isRegistered) {
            logEvent("event=start_already_registered")
            val selected = selectedPeer
            if (selected != null &&
                hid?.getConnectionState(selected) == BluetoothProfile.STATE_CONNECTED
            ) {
                peer = selected
                state = State.Connected(selected.address)
            } else if (state !is State.Connecting &&
                state !is State.Disconnecting
            ) {
                state = State.Ready
            }
            return true
        }
        if (registrationPending || (proxyRequested && hid == null)) {
            logEvent("event=start_already_pending")
            return true
        }
        hid?.let { existingProxy ->
            logEvent("event=start_reuse_proxy")
            return requestRegistration(existingProxy)
        }
        proxyRequested = true
        state = State.Registering
        logEvent("event=proxy_requested")
        val requested = runCatching {
            bluetooth.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        }.getOrDefault(false)
        if (!requested) {
            proxyRequested = false
            logEvent("event=proxy_request_failed")
            state = State.Error("HID proxy unavailable. Check Bluetooth, then retry START")
        }
        return requested
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
        if (disconnectRequested || state is State.Disconnecting) {
            state = State.Error("Previous host is disconnecting; try again afterward")
            return false
        }
        if (selectedPeerAddress != null &&
            selectedPeerAddress != device.address &&
            (state is State.Connecting || peer != null)
        ) {
            state = State.Error("Finish the previous host connection before switching devices")
            return false
        }
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

    /** Clear user authorization only after the host has actually disconnected. */
    private fun finishUserDisconnect() {
        disconnectRequested = false
        peer = null
        selectedPeer = null
        selectedPeerAddress = null
        logEvent("event=user_disconnect_complete")
        state = if (isRegistered) State.Ready else State.Idle
    }

    /**
     * Explicitly disconnect the current selected host without disabling Bluetooth or
     * unregistering the HID application. The asynchronous callback confirms completion.
     */
    fun disconnectSelected(): Boolean {
        if (!hasPermission() || !isRegistered) return false
        abortActiveSend()
        val device = selectedPeer ?: return false
        val service = hid ?: return false
        return when (service.getConnectionState(device)) {
            BluetoothProfile.STATE_DISCONNECTED -> {
                finishUserDisconnect()
                true
            }
            BluetoothProfile.STATE_DISCONNECTING -> {
                disconnectRequested = true
                peer = null
                state = State.Disconnecting(device.address)
                true
            }
            else -> {
                // Android's BluetoothHidDevice.disconnect() returns only request acceptance.
                val accepted = runCatching { service.disconnect(device) }.getOrDefault(false)
                if (accepted) {
                    disconnectRequested = true
                    peer = null
                    state = State.Disconnecting(device.address)
                    logEvent("event=user_disconnect_requested")
                    true
                } else if (service.getConnectionState(device) ==
                    BluetoothProfile.STATE_DISCONNECTED
                ) {
                    finishUserDisconnect()
                    true
                } else {
                    state = State.Error("Bluetooth stack rejected HID disconnect request")
                    false
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
                State.Disconnecting(device.address)
            }
            else -> {
                peer = null
                if (disconnectRequested) {
                    finishUserDisconnect()
                    return state
                }
                State.Error("Bluetooth HID profile is disconnected (pairing alone is not HID)")
            }
        }
        return state
    }

    /**
     * Fail closed on foreground loss, screen-off or HID state changes. A PARTIAL_WAKE_LOCK
     * keeps the CPU executing while the screen is on; it does NOT keep the Android HID
     * profile registered or prevent the hardware power button from turning off the screen.
     *
     * HID sendReport returns "submitted to Android", not "received by the remote host".
     * Never replay a partial send automatically: it could duplicate credential prefixes.
     */
    suspend fun sendAscii(text: String): SendResult = sendMutex.withLock {
        val strokes = UsKeyboardReport.encode(text)
            ?: return@withLock SendResult(
                SendStatus.UNSUPPORTED_TEXT, 0, text.length,
            ).also { lastSendResult = it }
        val count = strokes.size
        val power = context.getSystemService(PowerManager::class.java)
        val service = hid
        val destination = peer
        val initialAbort = sendAbortGeneration.get()
        val initialLink = linkGeneration.get()

        fun result(status: SendStatus, accepted: Int): SendResult =
            SendResult(status, accepted, count).also {
                lastSendResult = it
                logEvent("event=send_result status=${status.name} accepted=$accepted total=$count")
            }

        // No background sending. Enforce actual Android HID profile status, not just
        // a stale UI callback. Do not queue text while CONNECTING.
        if (!foregroundActive || power?.isInteractive != true) {
            return@withLock result(SendStatus.NOT_FOREGROUND, 0)
        }
        if (!hasPermission() || !isRegistered || service == null || destination == null ||
            state !is State.Connected || selectedPeerAddress != destination.address ||
            disconnectRequested ||
            service.getConnectionState(destination) != BluetoothProfile.STATE_CONNECTED
        ) {
            return@withLock result(SendStatus.NOT_CONNECTED, 0)
        }

        // Test text is short; bound the awake period even if an Android callback hangs.
        val timeoutMs = (count * 140L + 3000L).coerceIn(5000L, 60000L)
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VaultKey:HidSend")
        val acquired = runCatching {
            wakeLock.acquire(timeoutMs)
            true
        }.getOrDefault(false)
        if (!acquired) return@withLock result(SendStatus.INCOMPLETE_OR_UNCERTAIN, 0)

        var accepted = 0
        var keyDown = false
        val release = ByteArray(KEYBOARD_INPUT_BYTES)
        try {
            withContext(Dispatchers.IO) {
                for (stroke in strokes) {
                    val valid = foregroundActive && power.isInteractive &&
                        sendAbortGeneration.get() == initialAbort &&
                        linkGeneration.get() == initialLink &&
                        hasPermission() && isRegistered &&
                        hid === service && peer?.address == destination.address &&
                        state is State.Connected && !disconnectRequested &&
                        service.getConnectionState(destination) == BluetoothProfile.STATE_CONNECTED
                    if (!valid) return@withContext result(SendStatus.INCOMPLETE_OR_UNCERTAIN, accepted)

                    // No plaintext or key report content is ever logged.
                    val pressed = runCatching {
                        service.sendReport(destination, REPORT_ID, stroke)
                    }.getOrDefault(false)
                    if (!pressed) return@withContext result(SendStatus.INCOMPLETE_OR_UNCERTAIN, accepted)
                    accepted++
                    keyDown = true
                    delay(50L)
                    val released = runCatching {
                        service.sendReport(destination, REPORT_ID, release)
                    }.getOrDefault(false)
                    if (!released) return@withContext result(SendStatus.INCOMPLETE_OR_UNCERTAIN, accepted)
                    keyDown = false
                    delay(50L)
                }
                // Final check matters: screen-off or disconnect can occur after last
                // report was accepted but before the coroutine finishes.
                if (!foregroundActive || !power.isInteractive ||
                    sendAbortGeneration.get() != initialAbort ||
                    linkGeneration.get() != initialLink ||
                    !isRegistered || state !is State.Connected ||
                    service.getConnectionState(destination) != BluetoothProfile.STATE_CONNECTED
                ) {
                    result(SendStatus.INCOMPLETE_OR_UNCERTAIN, accepted)
                } else {
                    result(SendStatus.REPORTS_ACCEPTED, accepted)
                }
            }
        } finally {
            // On cancellation or interruption, never leave a logical key held down.
            if (keyDown && hasPermission()) {
                runCatching { service.sendReport(destination, REPORT_ID, release) }
            }
            if (wakeLock.isHeld) runCatching { wakeLock.release() }
            // The latest accepted count is retained even if the caller's Job was
            // cancelled by Activity destruction.
            if (lastSendResult == null) {
                lastSendResult = SendResult(SendStatus.INCOMPLETE_OR_UNCERTAIN, accepted, count)
            }
        }
    }

    fun close() {
        if (closed) return
        abortActiveSend()
        foregroundActive = false
        closed = true
        logEvent("event=transport_closed")
        if (hasPermission()) {
            // Only unregister if our request was accepted or the system confirmed it.
            if (isRegistered || registrationPending) {
                runCatching { hid?.unregisterApp() }
            }
            hid?.let {
                runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
            }
        }
        peer = null
        selectedPeer = null
        selectedPeerAddress = null
        disconnectRequested = false
        isRegistered = false
        registrationPending = false
        proxyRequested = false
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
