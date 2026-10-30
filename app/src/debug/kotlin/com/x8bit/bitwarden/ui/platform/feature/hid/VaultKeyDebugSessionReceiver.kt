package com.x8bit.bitwarden.ui.platform.feature.hid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.util.Log
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.SwitchAccountResult
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Debug APK ONLY: ADB-shell-only, real Bitwarden session transitions for race testing.
 *
 * This class and its exported manifest receiver do not exist in release builds.
 * Sender UID must equal Process.SHELL_UID, which requires a trusted ADB session.
 * Unknown UID (-1) is denied, including on vendor ROMs that obscure sender UID.
 * No arbitrary intents, credentials, file writes or fake VaultStateEvent emission.
 */
@AndroidEntryPoint
class VaultKeyDebugSessionReceiver : BroadcastReceiver() {
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var vaultLockManager: VaultLockManager

    override fun onReceive(context: Context, intent: Intent) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            getSentFromUid() != Process.SHELL_UID
        ) {
            Log.w(TAG, "event=debug_session_command_rejected")
            return
        }

        when (intent.action) {
            ACTION_LOCK -> {
                val active = authRepository.userStateFlow.value?.activeUserId
                if (active.isNullOrBlank()) {
                    Log.i(TAG, "event=debug_lock_no_active_account")
                    return
                }
                // Invoke the real manager: this updates lock state and emits
                // VaultStateEvent.Locked, unlike a fabricated debug event.
                vaultLockManager.lockVault(active, isUserInitiated = true)
                Log.i(TAG, "event=debug_real_lock_requested")
            }

            ACTION_SWITCH_OTHER -> {
                val userState = authRepository.userStateFlow.value
                if (userState == null) {
                    Log.i(TAG, "event=debug_switch_no_active_account")
                    return
                }
                val target = userState.accounts.firstOrNull {
                    it.userId != userState.activeUserId && it.isLoggedIn
                }
                if (target == null) {
                    Log.i(TAG, "event=debug_switch_no_other_logged_in_account")
                    return
                }
                // This is a genuine local active-account switch; no email/user
                // IDs are accepted from broadcast extras or emitted in logs.
                val outcome = authRepository.switchAccount(target.userId)
                val result = when (outcome) {
                    SwitchAccountResult.AccountSwitched -> "switched"
                    SwitchAccountResult.NoChange -> "unchanged"
                }
                Log.i(TAG, "event=debug_real_account_switch result=$result")
            }

            else -> Log.w(TAG, "event=debug_unknown_session_command")
        }
    }

    companion object {
        private const val TAG = "VaultKeyHID"
        const val ACTION_LOCK = "com.x8bit.bitwarden.dev.vaultkey.DEBUG_LOCK_VAULT"
        const val ACTION_SWITCH_OTHER = "com.x8bit.bitwarden.dev.vaultkey.DEBUG_SWITCH_OTHER"
    }
}
