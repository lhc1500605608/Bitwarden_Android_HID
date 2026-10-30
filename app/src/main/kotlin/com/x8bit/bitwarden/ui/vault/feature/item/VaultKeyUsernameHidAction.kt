package com.x8bit.bitwarden.ui.vault.feature.item

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import androidx.compose.runtime.LaunchedEffect
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
import com.x8bit.bitwarden.data.platform.manager.hid.HidOneShotVerification
import com.x8bit.bitwarden.ui.platform.composition.LocalBiometricsManager
import com.x8bit.bitwarden.ui.platform.manager.biometrics.BiometricsManager
import com.x8bit.bitwarden.ui.vault.feature.item.model.TotpCodeItemData
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Debug-only functional bridge from the unlocked Bitwarden Login item to Bluetooth HID.
 *
 * Requires a fresh Bitwarden biometric/device-credential user-verification
 * prompt for EACH send, followed by a second explicit receiving-host confirmation.
 * The grant is valid for only the selected vault item, Bluetooth host and HID
 * field; it expires after 30 seconds and is consumed before the send.
 *
 * This stays DEBUG-only and must be exercised with SYNTHETIC vault data.
 * Device verification is not equivalent to a policy-required master-password
 * reprompt; production security policy integration remains pending.
 * No credential in Intent, SavedStateHandle, clipboard, persisted state or logs.
 */
private enum class DebugHidField(
    val title: String,
    val grantField: HidOneShotVerification.Field,
) {
    USERNAME("username", HidOneShotVerification.Field.USERNAME),
    PASSWORD("password", HidOneShotVerification.Field.PASSWORD),
    TOTP("TOTP", HidOneShotVerification.Field.TOTP),
    LOGIN_COMBINED("username + TAB + password", HidOneShotVerification.Field.LOGIN_COMBINED),
}

@Composable
internal fun VaultKeyUsernameHidAction(
    vaultItemId: String,
    username: String?,
    password: String?,
    totpCodeItemData: TotpCodeItemData?,
    modifier: Modifier = Modifier,
    biometricsManager: BiometricsManager = LocalBiometricsManager.current,
    sessionGate: VaultKeySessionGateViewModel = hiltViewModel(),
) {
    // A runtime guard as well as the BuildConfig.DEBUG call-site gate.
    if (!BuildConfig.DEBUG) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val transport = remember(context, vaultItemId) {
        BluetoothHidKeyboard(context.applicationContext)
    }
    val scope = rememberCoroutineScope()
    // Fixed to the active account at this item screen's creation. A later
    // account switch may NEVER authorize this original screen's cipher.
    val expectedUserId = remember(vaultItemId) { sessionGate.captureActiveUserId() }
    var sessionRevoked by remember(vaultItemId) { mutableStateOf(false) }
    val verification = remember(vaultItemId) { HidOneShotVerification() }
    val mounted = remember(vaultItemId) { mutableStateOf(true) }
    // Increase on lifecycle/host change to invalidate late biometric callbacks.
    var verificationEpoch by remember(vaultItemId) { mutableStateOf(0) }
    var verifying by remember(vaultItemId) { mutableStateOf(false) }
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

    // Always include the selected device's actual Bluetooth address in confirmation:
    // a friendly name alone can be duplicated or stale.
    fun selectedHostDisplayName(): String {
        val host = selectedHost ?: return "No receiver selected"
        val liveName = if (transport.hasPermission()) {
            runCatching { host.name }.getOrNull()?.takeIf {
                it.isNotBlank() && !it.equals("Unknown", ignoreCase = true)
            }
        } else null
        val label = liveName ?: selectedHostLabel?.takeIf {
            it.isNotBlank() && it != host.address && !it.equals("Unknown", ignoreCase = true)
        }
        return if (label == null) host.address else "$label (${host.address})"
    }
    var hostPickerOpen by remember(vaultItemId) { mutableStateOf(false) }
    // Only FIELD TYPE is retained in UI state, never credential text.
    var pendingField by remember(vaultItemId) { mutableStateOf<DebugHidField?>(null) }
    var verificationExpiresAtMs by remember(vaultItemId) { mutableStateOf<Long?>(null) }
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
        mounted.value = true
        transport.onStateChanged = { next ->
            hidState = next
            if (next !is BluetoothHidKeyboard.State.Connected) {
                // A connection change revokes unconsumed authorizations and
                // any pending field dialog; no implicit resends on reconnect.
                verification.invalidate()
                verificationEpoch++
                verifying = false
                pendingField = null
                verificationExpiresAtMs = null
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> transport.setForegroundActive(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    transport.setForegroundActive(false)
                    verification.invalidate()
                    verificationEpoch++
                    verifying = false
                    pendingField = null
                    verificationExpiresAtMs = null
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
            verificationEpoch++
            verifying = false
            pendingField = null
            verificationExpiresAtMs = null
            transport.setForegroundActive(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
            transport.onStateChanged = null
            transport.close()
        }
    }

    // Reactive notification for account switches, vault locking and item
    // revocation. Independent synchronous checks below run at every HID key.
    LaunchedEffect(vaultItemId, expectedUserId, sessionGate) {
        merge(
            sessionGate.userStateFlow.map { Unit },
            sessionGate.vaultStateEventFlow.map { Unit },
            sessionGate.itemStateFlow(vaultItemId).map { Unit },
        ).collect {
            if (!sessionGate.isItemAvailable(expectedUserId, vaultItemId)) {
                sessionRevoked = true
                verification.invalidate()
                verificationEpoch++
                verifying = false
                pendingField = null
                verificationExpiresAtMs = null
                transport.abortActiveSend()
                resultMessage = "NOT SENT / INTERRUPTED: vault locked, account switched or item removed."
            }
        }
    }

    fun isCurrentFieldAllowed(field: DebugHidField): Boolean =
        !sessionRevoked &&
            sessionGate.isFieldAllowed(expectedUserId, vaultItemId, field.grantField)

    val connected = hidState is BluetoothHidKeyboard.State.Connected &&
        !sending && !verifying && !sessionRevoked &&
        sessionGate.isItemAvailable(expectedUserId, vaultItemId)
    fun requestField(field: DebugHidField) {
        val host = selectedHost
        val now = transport.state
        if (!connected || !isCurrentFieldAllowed(field) || host == null ||
            now !is BluetoothHidKeyboard.State.Connected || now.address != host.address ||
            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            resultMessage = "NOT SENT: selected HID host is not CONNECTED or page not foreground."
            return
        }
        if (!biometricsManager.isUserVerificationSupported) {
            resultMessage = "NOT SENT: strong biometrics/device credential verification unavailable."
            return
        }

        verification.invalidate()
        pendingField = null
        verificationExpiresAtMs = null
        verificationEpoch++
        val attempt = verificationEpoch
        verifying = true
        resultMessage = "Verify device identity for ${field.title} before sending."

        fun reject(reason: String) {
            if (verificationEpoch == attempt && mounted.value) {
                verification.invalidate()
                pendingField = null
                verificationExpiresAtMs = null
                verifying = false
                resultMessage = "NOT SENT: $reason"
            }
        }

        // Bitwarden's existing system prompt. There is no local bypass switch
        // and no "verified" flag that can be set from a UI automation click.
        biometricsManager.promptUserVerification(
            onSuccess = {
                val current = transport.state
                if (verificationEpoch == attempt && mounted.value && verifying &&
                    isCurrentFieldAllowed(field) &&
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    selectedHost?.address == host.address &&
                    current is BluetoothHidKeyboard.State.Connected &&
                    current.address == host.address
                ) {
                    val verifiedAt = SystemClock.elapsedRealtime()
                    verification.issue(
                        itemId = vaultItemId,
                        hostAddress = host.address,
                        field = field.grantField,
                        nowElapsedMs = verifiedAt,
                    )
                    verificationExpiresAtMs = verifiedAt + 30_000L
                    verifying = false
                    pendingField = field
                    resultMessage = "Verified. Confirm selected host within 30 seconds."
                } else {
                    reject("host, item, or foreground changed during verification.")
                }
            },
            onCancel = { reject("device verification cancelled.") },
            onLockOut = { reject("device verification locked out.") },
            onError = { reject("device verification failed.") },
            onNotSupported = { reject("device verification not supported.") },
        )
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("VaultKey HID · DEBUG VERIFIED FIELD SEND (test credentials only)")
        OutlinedButton(
            onClick = {
                if (!transport.hasPermission()) {
                    permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    transport.start()
                    pairedHosts = transport.pairedDevices()
                    verification.invalidate()
                    verificationEpoch++
                    verifying = false
                    selectedHost = null
                    selectedHostLabel = null
                    pendingField = null
                    verificationExpiresAtMs = null
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
        if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
            Button(
                enabled = connected,
                onClick = { requestField(DebugHidField.LOGIN_COMBINED) },
            ) { Text("Send username + Tab + password") }
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
                            onClick = {
                                verification.invalidate()
                                verificationEpoch++
                                verifying = false
                                pendingField = null
                                verificationExpiresAtMs = null
                                selectedHost = host
                                selectedHostLabel = name?.takeIf { it.isNotBlank() } ?: host.address
                            },
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
                        // Assign the target identity before requesting a potentially
                        // synchronous Bluetooth state transition.
                        verification.invalidate()
                        verificationEpoch++
                        verifying = false
                        verificationExpiresAtMs = null
                        // Store the unformatted name/address, not "name (address)",
                        // so confirmation never repeats the Bluetooth address.
                        selectedHostLabel = if (transport.hasPermission()) {
                            runCatching { host.name }.getOrNull()
                                ?.takeIf { it.isNotBlank() && it != "Unknown" }
                                ?: host.address
                        } else host.address
                        resultMessage = if (transport.connect(host)) {
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

    // Expiry must dismiss the confirmation itself. Otherwise a stale dialog
    // looks authorized and only fails after the user taps Send once.
    LaunchedEffect(pendingField, verificationExpiresAtMs) {
        val currentField = pendingField
        val deadlineMs = verificationExpiresAtMs
        if (currentField != null && deadlineMs != null) {
            delay((deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            if (pendingField == currentField && verificationExpiresAtMs == deadlineMs) {
                verification.invalidate()
                verificationEpoch++
                pendingField = null
                verificationExpiresAtMs = null
                resultMessage = "NOT SENT: verification expired. Verify device again."
            }
        }
    }

    val field = pendingField
    if (field != null) {
        AlertDialog(
            onDismissRequest = {
                pendingField = null
                verificationExpiresAtMs = null
                verification.invalidate()
                verificationEpoch++
            },
            title = { Text("Confirm VERIFIED HID " + field.title + " send") },
            text = {
                Text(
                    "Receiving host: " + selectedHostDisplayName() +
                        ". Confirm the focused field after device verification. " +
                        "Authorization lasts 30 seconds, one send only. " +
                        "DEBUG test credentials only; no automatic resend.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Capture only the field kind here. Read actual data at send instant.
                        pendingField = null
                        verificationExpiresAtMs = null
                        if (sending || verifying) {
                            verification.invalidate()
                            return@TextButton
                        }
                        val host = selectedHost
                        if (host == null || !verification.consume(
                                itemId = vaultItemId,
                                hostAddress = host.address,
                                field = field.grantField,
                                nowElapsedMs = SystemClock.elapsedRealtime(),
                            )
                        ) {
                            resultMessage = "NOT SENT: verification expired or item/host/field changed."
                            return@TextButton
                        }
                        if (!isCurrentFieldAllowed(field)) {
                            resultMessage = "NOT SENT: vault, account or field permissions changed."
                            return@TextButton
                        }
                        val now = transport.state
                        if (now !is BluetoothHidKeyboard.State.Connected ||
                            now.address != host.address ||
                            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        ) {
                            resultMessage = "NOT SENT: HID is not connected/foreground."
                            return@TextButton
                        }

                        // TOTP is re-evaluated AFTER confirmation; code and validity
                        // were never captured when the dialog first opened.
                        val value: String? = when (field) {
                            DebugHidField.USERNAME -> latestUsername
                            DebugHidField.PASSWORD -> latestPassword
                            DebugHidField.LOGIN_COMBINED -> {
                                val user = latestUsername
                                val pass = latestPassword
                                if (user.isNullOrEmpty() || pass.isNullOrEmpty()) {
                                    null
                                } else if (user.any { it == '\t' || it == '\n' || it == '\r' } ||
                                    pass.any { it == '\t' || it == '\n' || it == '\r' }
                                ) {
                                    null
                                } else {
                                    // One sendAscii call, one mutex, one wake lock and one
                                    // interruption outcome. Never split into two sends.
                                    "${user}\t${pass}"
                                }
                            }
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
                            } else if (field == DebugHidField.LOGIN_COMBINED) {
                                "NOT SENT: username and password must be present " +
                                    "and contain no embedded tab or line breaks."
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
                                resultMessage = transport.sendAscii(value) {
                                    isCurrentFieldAllowed(field)
                                }.summary()
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
                TextButton(
                    onClick = {
                        pendingField = null
                        verificationExpiresAtMs = null
                        verification.invalidate()
                        verificationEpoch++
                        resultMessage = "NOT SENT: confirmation cancelled. Verify again to send."
                    },
                ) { Text("Cancel") }
            },
        )
    }
}
