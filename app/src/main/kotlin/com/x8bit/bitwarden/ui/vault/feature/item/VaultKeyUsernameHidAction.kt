package com.x8bit.bitwarden.ui.vault.feature.item

import android.Manifest
import android.bluetooth.BluetoothDevice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.x8bit.bitwarden.BuildConfig
import com.x8bit.bitwarden.data.platform.manager.hid.BluetoothHidKeyboard
import com.x8bit.bitwarden.ui.vault.feature.item.model.TotpCodeItemData
import kotlinx.coroutines.launch

/**
 * Debug-only functional bridge from the unlocked Bitwarden Login item to Bluetooth HID.
 *
 * SECURITY GATE IS TEMPORARILY DEFERRED FOR AUTOMATED TESTING.
 * Use only synthetic credentials in a disposable vault; never production passwords.
 * The separate one-use verification implementation/tests stay in the repository
 * for re-enabling when host, field and end-to-end function tests pass.
 *
 * No credential in Intent, SavedStateHandle, clipboard, persisted state or logs.
 * A fresh explicit host/field confirmation is still required for each send.
 */
private enum class DebugHidField(val title: String) {
    USERNAME("username"),
    PASSWORD("password"),
    TOTP("TOTP"),
}

@Composable
internal fun VaultKeyUsernameHidAction(
    vaultItemId: String,
    username: String?,
    password: String?,
    totpCodeItemData: TotpCodeItemData?,
    modifier: Modifier = Modifier,
) {
    // A runtime guard as well as the BuildConfig.DEBUG call-site gate.
    if (!BuildConfig.DEBUG) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val transport = remember(context, vaultItemId) {
        BluetoothHidKeyboard(context.applicationContext)
    }
    val scope = rememberCoroutineScope()
    // Pull the CURRENT field at the final confirmation, never snapshot an OTP in a dialog.
    val latestUsername by rememberUpdatedState(username)
    val latestPassword by rememberUpdatedState(password)
    val latestTotp by rememberUpdatedState(totpCodeItemData)

    var hidState by remember(vaultItemId) {
        mutableStateOf<BluetoothHidKeyboard.State>(BluetoothHidKeyboard.State.Idle)
    }
    var pairedHosts by remember(vaultItemId) { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var selectedHost by remember(vaultItemId) { mutableStateOf<BluetoothDevice?>(null) }
    var selectedHostLabel by remember(vaultItemId) { mutableStateOf<String?>(null) }
    var hostPickerOpen by remember(vaultItemId) { mutableStateOf(false) }
    // Only FIELD TYPE is retained in UI state, never credential text.
    var pendingField by remember(vaultItemId) { mutableStateOf<DebugHidField?>(null) }
    var sending by remember(vaultItemId) { mutableStateOf(false) }
    var resultMessage by remember(vaultItemId) {
        mutableStateOf("DEBUG TEST ONLY — synthetic credentials; select a paired host.")
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            transport.start()
            resultMessage = "Bluetooth permission granted; wait for HID Ready."
        } else {
            resultMessage = "Nearby devices permission denied."
        }
    }

    DisposableEffect(transport, lifecycleOwner, vaultItemId) {
        transport.onStateChanged = { hidState = it }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> transport.setForegroundActive(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    transport.setForegroundActive(false)
                    // Never resume an unconfirmed transfer when the vault is backgrounded.
                    pendingField = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            transport.setForegroundActive(true)
        }
        onDispose {
            pendingField = null
            transport.setForegroundActive(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
            transport.onStateChanged = null
            transport.close()
        }
    }

    val connected = hidState is BluetoothHidKeyboard.State.Connected && !sending
    fun requestField(field: DebugHidField) {
        if (!connected || selectedHost == null) {
            resultMessage = "NOT SENT: select and connect the intended receiving computer."
            return
        }
        pendingField = field
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("VaultKey HID · DEBUG FUNCTION TEST — NO BIOMETRIC GATE")
        OutlinedButton(
            onClick = {
                if (!transport.hasPermission()) {
                    permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    transport.start()
                    pairedHosts = transport.pairedDevices()
                    selectedHost = null
                    selectedHostLabel = null
                    pendingField = null
                    hostPickerOpen = true
                }
            },
        ) { Text("HID host") }

        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (!username.isNullOrEmpty()) {
                Button(
                    enabled = connected,
                    onClick = { requestField(DebugHidField.USERNAME) },
                ) { Text("Send username") }
            }
            if (!password.isNullOrEmpty()) {
                Button(
                    enabled = connected,
                    onClick = { requestField(DebugHidField.PASSWORD) },
                ) { Text("Send password") }
            }
        }
        if (totpCodeItemData != null) {
            Button(
                enabled = connected,
                onClick = { requestField(DebugHidField.TOTP) },
            ) { Text("Send current TOTP") }
        }
        Text(resultMessage, modifier = Modifier.padding(bottom = 8.dp))
    }

    if (hostPickerOpen) {
        AlertDialog(
            onDismissRequest = { hostPickerOpen = false },
            title = { Text("Select test HID receiver") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select a computer/tablet, not headphones. No default selection.")
                    Text(
                        "HID: " + when (hidState) {
                            is BluetoothHidKeyboard.State.Ready -> "Ready"
                            is BluetoothHidKeyboard.State.Connected -> "Connected"
                            is BluetoothHidKeyboard.State.Registering -> "Registering"
                            is BluetoothHidKeyboard.State.Connecting -> "Connecting"
                            is BluetoothHidKeyboard.State.Disconnecting -> "Disconnecting"
                            is BluetoothHidKeyboard.State.Error -> "Error"
                            else -> "Not registered"
                        },
                    )
                    OutlinedButton(
                        onClick = { pairedHosts = transport.pairedDevices() },
                    ) { Text("Refresh paired devices") }
                    pairedHosts.forEach { host ->
                        val name = if (transport.hasPermission()) {
                            runCatching { host.name }.getOrNull()
                        } else null
                        OutlinedButton(
                            onClick = { selectedHost = host },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                (if (selectedHost?.address == host.address) "✓ " else "") +
                                    (name ?: "Unknown") + " / " + host.address,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedHost != null &&
                        hidState is BluetoothHidKeyboard.State.Ready,
                    onClick = {
                        val host = selectedHost ?: return@TextButton
                        resultMessage = if (transport.connect(host)) {
                            selectedHostLabel = if (transport.hasPermission()) {
                                runCatching { host.name }.getOrNull() ?: host.address
                            } else host.address
                            "HID connecting; wait for Connected before sending."
                        } else {
                            "HID connect rejected; check target and connection state."
                        }
                        hostPickerOpen = false
                    },
                ) { Text("Connect selected host") }
            },
            dismissButton = {
                TextButton(onClick = { hostPickerOpen = false }) { Text("Cancel") }
            },
        )
    }

    val field = pendingField
    if (field != null) {
        AlertDialog(
            onDismissRequest = { pendingField = null },
            title = { Text("Confirm DEBUG HID " + field.title + " send") },
            text = {
                Text(
                    "Receiving host: " + (selectedHostLabel ?: "Unknown") +
                        ". Confirm its focused input field. DEBUG TEST ONLY: there is no " +
                        "biometric reauthentication. Never use a real password here. " +
                        "Sending is not queued or automatically retried.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Capture only the field kind here. Read actual data at send instant.
                        pendingField = null
                        if (sending) return@TextButton
                        val host = selectedHost
                        val now = transport.state
                        if (host == null || now !is BluetoothHidKeyboard.State.Connected ||
                            now.address != host.address ||
                            !lifecycleOwner.lifecycle.currentState.isAtLeast(
                                Lifecycle.State.RESUMED
                            )
                        ) {
                            resultMessage = "NOT SENT: HID is not connected/foreground."
                            return@TextButton
                        }

                        // TOTP is re-evaluated AFTER confirmation; code and validity
                        // were never captured when the dialog first opened.
                        val value: String? = when (field) {
                            DebugHidField.USERNAME -> latestUsername
                            DebugHidField.PASSWORD -> latestPassword
                            DebugHidField.TOTP -> latestTotp?.let { totp ->
                                if (totp.timeLeftSeconds < 5) null
                                else totp.verificationCode.takeIf {
                                    it.length in 6..8 && it.all(Char::isDigit)
                                }
                            }
                        }
                        if (value.isNullOrEmpty()) {
                            resultMessage = if (field == DebugHidField.TOTP) {
                                "NOT SENT: current TOTP unavailable or expires in under 5 seconds."
                            } else {
                                "NOT SENT: selected field is unavailable."
                            }
                            return@TextButton
                        }
                        sending = true
                        scope.launch {
                            try {
                                // Transport checks the real CONNECTED profile and refuses
                                // sending while CONNECTING. It stops on screen-off without replay.
                                resultMessage = transport.sendAscii(value).summary()
                            } catch (_: Exception) {
                                resultMessage = "INCOMPLETE / UNKNOWN. Check the receiving field."
                            } finally {
                                sending = false
                            }
                        }
                    },
                ) { Text("Send once") }
            },
            dismissButton = {
                TextButton(onClick = { pendingField = null }) { Text("Cancel") }
            },
        )
    }
}
