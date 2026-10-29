package com.x8bit.bitwarden.ui.vault.feature.item

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.SystemClock
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.x8bit.bitwarden.data.platform.manager.hid.BluetoothHidKeyboard
import com.x8bit.bitwarden.data.platform.manager.hid.HidOneShotVerification
import com.x8bit.bitwarden.ui.platform.composition.LocalBiometricsManager
import com.x8bit.bitwarden.ui.platform.manager.biometrics.BiometricsManager
import kotlinx.coroutines.launch

/**
 * First incremental vault integration: DEBUG only, username only, explicitly selected host.
 *
 * Do not add passwords here until separate on-device reauthentication and a fresh
 * confirmation gate are implemented and tested. No secrets in Intents, clipboard,
 * logs, SavedStateHandle, or persistent state.
 */
@Composable
internal fun VaultKeyUsernameHidAction(
    vaultItemId: String,
    username: String,
    modifier: Modifier = Modifier,
    biometricsManager: BiometricsManager = LocalBiometricsManager.current,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val transport = remember(context, vaultItemId) {
        BluetoothHidKeyboard(context.applicationContext)
    }
    val verification = remember(vaultItemId) { HidOneShotVerification() }
    // A late biometric callback must not grant access after navigation/disposal.
    val mounted = remember(vaultItemId) { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()
    var hidState by remember(vaultItemId) {
        mutableStateOf<BluetoothHidKeyboard.State>(BluetoothHidKeyboard.State.Idle)
    }
    var pairedHosts by remember(vaultItemId) { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var selectedHost by remember(vaultItemId) { mutableStateOf<BluetoothDevice?>(null) }
    var selectedHostLabel by remember(vaultItemId) { mutableStateOf<String?>(null) }
    var showHostPicker by remember(vaultItemId) { mutableStateOf(false) }
    var showSendConfirmation by remember(vaultItemId) { mutableStateOf(false) }
    var sending by remember(vaultItemId) { mutableStateOf(false) }
    var resultMessage by remember(vaultItemId) {
        mutableStateOf("Choose a paired computer or tablet first.")
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        resultMessage = if (granted) {
            transport.start()
            "Bluetooth permission granted. Connect after HID reports Ready."
        } else {
            "Nearby devices permission denied."
        }
    }

    DisposableEffect(transport, lifecycleOwner, vaultItemId) {
        mounted.value = true
        transport.onStateChanged = { hidState = it }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> transport.setForegroundActive(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    transport.setForegroundActive(false)
                    verification.invalidate()
                    showSendConfirmation = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            transport.setForegroundActive(true)
        }
        onDispose {
            mounted.value = false
            verification.invalidate()
            transport.setForegroundActive(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
            transport.onStateChanged = null
            transport.close()
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("VaultKey HID · Debug username integration")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (!transport.hasPermission()) {
                        permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        transport.start()
                        pairedHosts = transport.pairedDevices()
                        verification.invalidate()
                        showSendConfirmation = false
                        selectedHost = null
                        selectedHostLabel = null
                        showHostPicker = true
                    }
                },
            ) {
                Text("HID host")
            }
            Button(
                enabled = !sending && username.isNotEmpty() && vaultItemId.isNotBlank() &&
                    hidState is BluetoothHidKeyboard.State.Connected,
                onClick = {
                    val target = selectedHost
                    val actual = transport.state
                    if (target == null || actual !is BluetoothHidKeyboard.State.Connected ||
                        actual.address != target.address
                    ) {
                        resultMessage = "NOT SENT: selected HID host not connected."
                        return@Button
                    }
                    if (!biometricsManager.isUserVerificationSupported) {
                        resultMessage = "NOT SENT: strong biometrics/device credentials unavailable."
                        return@Button
                    }
                    verification.invalidate()
                    resultMessage = "Waiting for device identity verification."
                    biometricsManager.promptUserVerification(
                        onSuccess = {
                            val stateNow = transport.state
                            if (!mounted.value ||
                                !lifecycleOwner.lifecycle.currentState.isAtLeast(
                                    Lifecycle.State.RESUMED
                                ) ||
                                selectedHost?.address != target.address ||
                                stateNow !is BluetoothHidKeyboard.State.Connected ||
                                stateNow.address != target.address
                            ) {
                                verification.invalidate()
                                resultMessage = "NOT SENT: page/host changed during verification."
                            } else {
                                verification.issue(
                                    itemId = vaultItemId,
                                    hostAddress = target.address,
                                    field = HidOneShotVerification.Field.USERNAME,
                                    nowElapsedMs = SystemClock.elapsedRealtime(),
                                )
                                showSendConfirmation = true
                                resultMessage = "Verified. Confirm destination within 15 seconds."
                            }
                        },
                        onCancel = {
                            verification.invalidate()
                            resultMessage = "NOT SENT: identity verification cancelled."
                        },
                        onLockOut = {
                            verification.invalidate()
                            resultMessage = "NOT SENT: identity verification locked out."
                        },
                        onError = {
                            verification.invalidate()
                            resultMessage = "NOT SENT: identity verification failed."
                        },
                        onNotSupported = {
                            verification.invalidate()
                            resultMessage = "NOT SENT: strong verification unavailable."
                        },
                    )
                },
            ) {
                Text("Verify + send username")
            }
        }
        Text(
            text = resultMessage,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }

    if (showHostPicker) {
        AlertDialog(
            onDismissRequest = { showHostPicker = false },
            title = { Text("Select Bluetooth HID receiver") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Do not select headphones. Select a computer/tablet explicitly.")
                    Text("HID state: " + when (hidState) {
                        is BluetoothHidKeyboard.State.Ready -> "Ready"
                        is BluetoothHidKeyboard.State.Connected -> "Connected"
                        is BluetoothHidKeyboard.State.Registering -> "Registering"
                        is BluetoothHidKeyboard.State.Connecting -> "Connecting"
                        is BluetoothHidKeyboard.State.Disconnecting -> "Disconnecting"
                        is BluetoothHidKeyboard.State.Error -> "Error"
                        else -> "Not registered"
                    })
                    OutlinedButton(
                        onClick = { pairedHosts = transport.pairedDevices() },
                    ) { Text("Refresh paired devices") }
                    pairedHosts.forEach { host ->
                        val label = if (transport.hasPermission()) {
                            runCatching { host.name }.getOrNull()
                        } else null
                        OutlinedButton(
                            onClick = { selectedHost = host },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                (if (selectedHost?.address == host.address) "✓ " else "") +
                                    (label ?: "Unknown device") + " / " + host.address,
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
                        verification.invalidate()
                        resultMessage = if (transport.connect(host)) {
                            selectedHostLabel = if (transport.hasPermission()) {
                                runCatching { host.name }.getOrNull() ?: host.address
                            } else host.address
                            "HID connection requested. Wait for Connected before sending."
                        } else {
                            "HID connection rejected; check the chosen host and profile state."
                        }
                        showHostPicker = false
                    },
                ) { Text("Connect selected host") }
            },
            dismissButton = {
                TextButton(onClick = { showHostPicker = false }) { Text("Cancel") }
            },
        )
    }

    if (showSendConfirmation) {
        AlertDialog(
            onDismissRequest = {
                showSendConfirmation = false
                verification.invalidate()
            },
            title = { Text("Confirm verified HID username input") },
            text = {
                Text(
                    "Receiving host: " + (selectedHostLabel ?: "Unknown") +
                        ". Verify its focused input field before sending. " +
                        "A username may be private. No clipboard will be used. " +
                        "If the target is not CONNECTED at confirmation time, nothing is queued.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSendConfirmation = false
                        if (sending) return@TextButton
                        val host = selectedHost
                        if (host == null || !verification.consume(
                                itemId = vaultItemId,
                                hostAddress = host.address,
                                field = HidOneShotVerification.Field.USERNAME,
                                nowElapsedMs = SystemClock.elapsedRealtime(),
                            )
                        ) {
                            resultMessage = "NOT SENT: verification expired or host/item changed."
                            return@TextButton
                        }
                        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(
                                Lifecycle.State.RESUMED
                            )
                        ) {
                            resultMessage = "NOT SENT: page is not foreground."
                            return@TextButton
                        }
                        sending = true
                        coroutineScope.launch {
                            try {
                                // Transport verifies the true Bluetooth profile at send time.
                                resultMessage = transport.sendAscii(username).summary()
                            } catch (_: Exception) {
                                resultMessage = "INCOMPLETE / UNKNOWN. Check receiving field."
                            } finally {
                                sending = false
                            }
                        }
                    },
                ) { Text("Send once") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSendConfirmation = false
                        verification.invalidate()
                    },
                ) { Text("Cancel") }
            },
        )
    }
}
