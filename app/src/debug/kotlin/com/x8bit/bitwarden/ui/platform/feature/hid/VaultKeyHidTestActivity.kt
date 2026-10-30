package com.x8bit.bitwarden.ui.platform.feature.hid

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.x8bit.bitwarden.data.platform.manager.hid.BluetoothHidKeyboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Debug-variant-only launcher. No vault access, user credentials, clipboard, or exported services.
 * Sends ONE fixed, harmless ASCII sample after the user selects a bonded peer and confirms.
 */
class VaultKeyHidTestActivity : Activity() {
    private lateinit var keyboard: BluetoothHidKeyboard
    private lateinit var statusText: TextView
    private lateinit var peerSpinner: Spinner
    private lateinit var sendButton: Button
    private lateinit var ampersandButton: Button
    private lateinit var probeButton: Button
    private lateinit var sendHintText: TextView
    private lateinit var sendResultText: TextView
    private var devices: List<BluetoothDevice> = emptyList()
    private var screenOffReceiverRegistered = false
    private var sendInProgress = false
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && ::keyboard.isInitialized) {
                // Screen lock may unregister the Bluetooth HID profile even with a CPU wake lock.
                keyboard.abortActiveSend()
                if (sendInProgress && ::sendResultText.isInitialized) {
                    sendResultText.text =
                        "Screen off during send: INCOMPLETE / UNKNOWN. Do not auto-retry."
                }
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (20 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "VaultKey — Bluetooth HID test"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "Debug build only. Does not read or transmit any vault secrets. " +
                "Forget the previous Bluetooth pairing on BOTH devices, then pair again; " +
                "the host may have cached an older mouse/keyboard descriptor. " +
                "Open a blank text editor with the US keyboard layout and send only " +
                "the single letter first."
            textSize = 15f
        })
        statusText = TextView(this).apply {
            text = "Tap Start HID. Android 12+ and an enabled Bluetooth adapter are required."
            textSize = 16f
        }
        root.addView(statusText)

        peerSpinner = Spinner(this)
        root.addView(peerSpinner)
        addButton(root, "1. Start Bluetooth HID (safe to repeat)") { startHid() }
        addButton(root, "2. Refresh paired devices") { refreshBondedDevices() }
        addButton(root, "3. Connect selected device") { connectSelectedDevice() }
        addButton(root, "4. Disconnect current HID host") { disconnectCurrentHost() }
        addButton(root, "5. Check HID connection status") { checkHidConnection() }
        probeButton = addButton(root, "6. Send one letter: a") {
            confirmSendTestText("a")
        }
        probeButton.isEnabled = false
        sendButton = addButton(root, "7. Send full test text") {
            confirmSendTestText(TEST_TEXT)
        }
        sendButton.isEnabled = false
        ampersandButton = addButton(root, "8. Send 27-byte test (ending &)") {
            confirmSendTestText(TEST_TEXT_WITH_AMPERSAND)
        }
        ampersandButton.isEnabled = false
        sendHintText = TextView(this).apply {
            text = "Sending unavailable: tap Start HID, select a paired host, " +
                "and wait for Connected before using a test button."
            textSize = 14f
        }
        root.addView(sendHintText)
        sendResultText = TextView(this).apply {
            text = "No HID send attempted."
            textSize = 15f
        }
        root.addView(sendResultText)
        root.addView(TextView(this).apply {
            text = "Baseline (26 bytes): " + TEST_TEXT +
                "\nExtended (27 bytes): " + TEST_TEXT_WITH_AMPERSAND +
                "\nUse a plain editor with ABC/US English input; non-English IMEs " +
                "can transform punctuation and letters." +
                "\nNo clipboard access. This is separate from the real Bitwarden vault."
            textSize = 14f
        })

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            statusText.text = "This debug prototype currently requires Android 12 or newer."
            return
        }
        keyboard = BluetoothHidKeyboard(this)
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        screenOffReceiverRegistered = true
        keyboard.onStateChanged = { state ->
            runOnUiThread {
                statusText.text = when (state) {
                    BluetoothHidKeyboard.State.Idle ->
                        "HID not registered. Enable Bluetooth and tap START if needed."
                    BluetoothHidKeyboard.State.Registering ->
                        "Registering HID profile. Repeated START is ignored while pending."
                    BluetoothHidKeyboard.State.Ready ->
                        "HID ready. Repeated START is safe; select and connect a paired target."
                    is BluetoothHidKeyboard.State.Connecting ->
                        "HID profile connecting to " + state.address +
                            ". If stuck, tap Check HID connection status."
                    is BluetoothHidKeyboard.State.Disconnecting ->
                        "Disconnecting selected HID host " + state.address + "..."
                    is BluetoothHidKeyboard.State.Connected ->
                        "Connected to " + state.address + ". Focus a blank field on the target."
                    is BluetoothHidKeyboard.State.Error -> "Error: " + state.reason
                }
                sendButton.isEnabled = state is BluetoothHidKeyboard.State.Connected
                probeButton.isEnabled = sendButton.isEnabled
                ampersandButton.isEnabled = sendButton.isEnabled
                sendHintText.text = if (sendButton.isEnabled) {
                    "Connected: focus the target editor and confirm each test send."
                } else {
                    "Sending unavailable until a paired host is connected. " +
                        "Tap Start HID, Refresh, then Connect selected device."
                }
            }
        }
    }

    private fun addButton(root: LinearLayout, label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
            root.addView(this)
        }

    private fun startHid() {
        if (!::keyboard.isInitialized) return
        if (!keyboard.hasPermission()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_REQUEST)
            return
        }
        if (!keyboard.start()) {
            statusText.text = "Unable to start HID. Enable Bluetooth and try again."
        }
    }

    private fun refreshBondedDevices() {
        if (!::keyboard.isInitialized || !keyboard.hasPermission()) {
            statusText.text = "Grant Nearby devices permission first."
            return
        }
        devices = keyboard.pairedDevices().sortedBy { it.name ?: it.address }
        // Index 0 is NOT a device. Never silently choose the first paired item:
        // Bluetooth bonded lists can include headphones and other non-HID hosts.
        val choices = listOf("Select a computer/tablet (nothing selected)") + devices.map { device ->
            (device.name ?: "Unnamed device") + " (" + device.address + ")"
        }
        peerSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            choices,
        )
        peerSpinner.setSelection(0, false)
        statusText.text = if (devices.isEmpty()) {
            "No bonded devices. Pair a computer or tablet using Bluetooth settings."
        } else {
            "Choose the computer/tablet from the dropdown. Headphones are not HID hosts."
        }
    }

    private fun connectSelectedDevice() {
        if (!::keyboard.isInitialized) return
        // Spinner index 0 is an explicit placeholder, not the first paired device.
        val device = devices.getOrNull(peerSpinner.selectedItemPosition - 1)
        if (device == null) {
            statusText.text = "No host selected. Open the dropdown and choose your computer."
            return
        }
        val hostLabel = (device.name ?: "Unnamed device") + " (" + device.address + ")"
        AlertDialog.Builder(this)
            .setTitle("Confirm keyboard destination")
            .setMessage(
                "Connect the HID keyboard to " + hostLabel + "? " +
                    "Only select a computer or tablet capable of receiving keyboard input. " +
                    "Do not choose headphones or audio devices.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Connect") { _, _ ->
                if (!keyboard.connect(device)) {
                    statusText.text = "HID connection request rejected for " + hostLabel +
                        ". Check the host type and HID connection status."
                }
            }
            .show()
    }

    private fun disconnectCurrentHost() {
        if (!::keyboard.isInitialized) return
        val ok = keyboard.disconnectSelected()
        if (!ok) {
            statusText.text = "No active host to disconnect, or the HID stack rejected " +
                "the request. Use Check HID connection status for details."
        }
    }

    private fun checkHidConnection() {
        if (!::keyboard.isInitialized) return
        if (!keyboard.hasPermission()) {
            statusText.text = "Nearby devices permission is missing."
            return
        }
        val actual = keyboard.refreshConnectionState()
        val stateLabel = when (actual) {
            BluetoothHidKeyboard.State.Idle -> "HID not registered."
            BluetoothHidKeyboard.State.Registering -> "Registering HID profile..."
            BluetoothHidKeyboard.State.Ready -> "No host selected. Select a host, then connect."
            is BluetoothHidKeyboard.State.Connecting ->
                "Android HID profile reports CONNECTING to " + actual.address
            is BluetoothHidKeyboard.State.Disconnecting ->
                "Android HID profile reports DISCONNECTING from " + actual.address
            is BluetoothHidKeyboard.State.Connected ->
                "Android HID profile reports CONNECTED to " + actual.address
            is BluetoothHidKeyboard.State.Error -> "HID status: " + actual.reason
        }
        statusText.text = stateLabel + "\n" + keyboard.diagnosticSummary()
    }

    private fun confirmSendTestText(textToSend: String) {
        if (!::keyboard.isInitialized) return
        if (keyboard.state !is BluetoothHidKeyboard.State.Connected) {
            sendResultText.text = "NOT SENT: HID must be CONNECTED (not CONNECTING)."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Send harmless test string?")
            .setMessage(
                "Confirm the target device and focused text field. " +
                    "This will type: " + textToSend,
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ ->
                sendInProgress = true
                sendResultText.text = "Sending HID reports. Keep the phone unlocked and foreground."
                sendButton.isEnabled = false
                probeButton.isEnabled = false
                ampersandButton.isEnabled = false
                scope.launch {
                    try {
                        val outcome = keyboard.sendAscii(textToSend)
                        // This stays separate from connection status callbacks, so a race
                        // cannot overwrite failure with a stale 'Connected' label.
                        sendResultText.text = outcome.summary()
                    } catch (exception: Exception) {
                        // All exceptions and partial sends are uncertain, never a success.
                        sendResultText.text = keyboard.lastSendResult?.summary()
                            ?: "INCOMPLETE / UNKNOWN: HID send interrupted. Do not auto-retry."
                    } finally {
                        sendInProgress = false
                        sendButton.isEnabled =
                            keyboard.state is BluetoothHidKeyboard.State.Connected
                        probeButton.isEnabled = sendButton.isEnabled
                        ampersandButton.isEnabled = sendButton.isEnabled
                    }
                }
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (::keyboard.isInitialized) {
            keyboard.setForegroundActive(true)
            if (!sendInProgress && ::sendResultText.isInitialized) {
                keyboard.lastSendResult?.let { sendResultText.text = it.summary() }
            }
        }
    }

    override fun onPause() {
        if (::keyboard.isInitialized) {
            keyboard.setForegroundActive(false)
            if (sendInProgress && ::sendResultText.isInitialized) {
                sendResultText.text =
                    "Activity paused during send: INCOMPLETE / UNKNOWN. Do not auto-retry."
            }
        }
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLUETOOTH_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startHid()
        } else if (requestCode == BLUETOOTH_REQUEST) {
            statusText.text = "Nearby devices permission denied."
        }
    }

    override fun onDestroy() {
        if (screenOffReceiverRegistered) {
            unregisterReceiver(screenOffReceiver)
            screenOffReceiverRegistered = false
        }
        if (::keyboard.isInitialized) keyboard.abortActiveSend()
        scope.cancel()
        if (::keyboard.isInitialized) {
            keyboard.onStateChanged = null
            keyboard.close()
        }
        super.onDestroy()
    }

    companion object {
        private const val BLUETOOTH_REQUEST = 9201
        // Exactly 26 ASCII bytes; previous macOS report of a missing & was
        // due to comparing against a 27-byte expectation not present in the APK.
        private const val TEST_TEXT = "VaultKey-Test Aa09 !@#-_=+"
        private const val TEST_TEXT_WITH_AMPERSAND = TEST_TEXT + "&"
    }
}
