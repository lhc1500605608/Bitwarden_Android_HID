package com.x8bit.bitwarden.ui.platform.feature.hid

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var probeButton: Button
    private var devices: List<BluetoothDevice> = emptyList()
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
        addButton(root, "1. Start Bluetooth HID") { startHid() }
        addButton(root, "2. Refresh paired devices") { refreshBondedDevices() }
        addButton(root, "3. Connect selected device") { connectSelectedDevice() }
        addButton(root, "4. Check HID connection status") { checkHidConnection() }
        probeButton = addButton(root, "5. Send one letter: a") {
            confirmSendTestText("a")
        }
        probeButton.isEnabled = false
        sendButton = addButton(root, "6. Send full test text") {
            confirmSendTestText(TEST_TEXT)
        }
        sendButton.isEnabled = false
        root.addView(TextView(this).apply {
            text = "Test: " + TEST_TEXT +
                "\nNo clipboard access. Bluetooth HID cannot validate where the host cursor is. " +
                "This test interface is separate from your real Bitwarden vault."
            textSize = 14f
        })

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            statusText.text = "This debug prototype currently requires Android 12 or newer."
            return
        }
        keyboard = BluetoothHidKeyboard(this)
        keyboard.onStateChanged = { state ->
            runOnUiThread {
                statusText.text = when (state) {
                    BluetoothHidKeyboard.State.Idle -> "HID not registered."
                    BluetoothHidKeyboard.State.Registering -> "Registering HID profile..."
                    BluetoothHidKeyboard.State.Ready -> "HID ready; choose a paired target."
                    is BluetoothHidKeyboard.State.Connecting ->
                        "HID profile connecting to " + state.address +
                            ". If stuck, tap Check HID connection status."
                    is BluetoothHidKeyboard.State.Connected ->
                        "Connected to " + state.address + ". Focus a blank field on the target."
                    is BluetoothHidKeyboard.State.Error -> "Error: " + state.reason
                }
                sendButton.isEnabled = state is BluetoothHidKeyboard.State.Connected
                probeButton.isEnabled = sendButton.isEnabled
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
        val choices = devices.map { device ->
            (device.name ?: "Unnamed device") + " (" + device.address + ")"
        }
        peerSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            if (choices.isEmpty()) listOf("No paired devices") else choices,
        )
        if (choices.isEmpty()) {
            statusText.text = "Pair the computer or tablet in Android Bluetooth settings first."
        }
    }

    private fun connectSelectedDevice() {
        if (!::keyboard.isInitialized) return
        val device = devices.getOrNull(peerSpinner.selectedItemPosition)
        if (device == null) {
            statusText.text = "No paired device selected."
            return
        }
        // BluetoothHidKeyboard emits the true profile state: CONNECTING, CONNECTED or error.
        // Do not overwrite its state callback with a speculative "Connecting" label.
        if (!keyboard.connect(device)) {
            statusText.text = "Connection request rejected. Tap Check HID connection status."
        }
    }

    private fun checkHidConnection() {
        if (!::keyboard.isInitialized) return
        if (!keyboard.hasPermission()) {
            statusText.text = "Nearby devices permission is missing."
            return
        }
        val actual = keyboard.refreshConnectionState()
        statusText.text = when (actual) {
            BluetoothHidKeyboard.State.Idle -> "HID not registered."
            BluetoothHidKeyboard.State.Registering -> "Registering HID profile..."
            BluetoothHidKeyboard.State.Ready -> "No host selected. Select a host, then connect."
            is BluetoothHidKeyboard.State.Connecting ->
                "Android HID profile reports CONNECTING to " + actual.address
            is BluetoothHidKeyboard.State.Connected ->
                "Android HID profile reports CONNECTED to " + actual.address
            is BluetoothHidKeyboard.State.Error -> "HID status: " + actual.reason
        }
    }

    private fun confirmSendTestText(textToSend: String) {
        if (!::keyboard.isInitialized ||
            keyboard.state !is BluetoothHidKeyboard.State.Connected
        ) return
        AlertDialog.Builder(this)
            .setTitle("Send harmless test string?")
            .setMessage(
                "Confirm the target device and focused text field. " +
                    "This will type: " + textToSend,
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ ->
                sendButton.isEnabled = false
                probeButton.isEnabled = false
                scope.launch {
                    try {
                        val accepted = keyboard.sendAscii(textToSend)
                        statusText.text = if (accepted) {
                            "Key reports sent. Verify the exact text on the target."
                        } else {
                            "Send failed; check pairing and connection."
                        }
                    } catch (exception: Exception) {
                        statusText.text = "Send interrupted. Check connection."
                    } finally {
                        sendButton.isEnabled =
                            keyboard.state is BluetoothHidKeyboard.State.Connected
                        probeButton.isEnabled = sendButton.isEnabled
                    }
                }
            }
            .show()
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
        scope.cancel()
        if (::keyboard.isInitialized) {
            keyboard.onStateChanged = null
            keyboard.close()
        }
        super.onDestroy()
    }

    companion object {
        private const val BLUETOOTH_REQUEST = 9201
        private const val TEST_TEXT = "VaultKey-Test Aa09 !@#-_=+"
    }
}
