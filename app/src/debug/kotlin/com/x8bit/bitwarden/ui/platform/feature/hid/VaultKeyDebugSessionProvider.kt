package com.x8bit.bitwarden.ui.platform.feature.hid

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.SwitchAccountResult
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Only included in src/debug. Hilt does not inject ContentProvider subclasses
 * directly; resolve application-scoped managers via a supported EntryPoint.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface VaultKeyDebugSessionEntryPoint {
    fun authRepository(): AuthRepository
    fun vaultLockManager(): VaultLockManager
}

/**
 * Debug-only ADB race-test hook. Calls real Bitwarden lock/account-switch APIs.
 *
 * ContentProvider.call() runs on an incoming Binder transaction. Capture
 * Binder.getCallingUid() at the entry before invoking any application logic.
 * Do NOT trust caller-provided arguments, packages, or broadcast sentFromUid.
 *
 * Only UID 2000 (ADB shell) may invoke the hook, never root (UID 0) or a
 * regular app. Nothing is exported in the Release manifest.
 */
class VaultKeyDebugSessionProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val callingUid = Binder.getCallingUid()
        val ctx = context
        val sdk = Build.VERSION.SDK_INT
        val debuggable = ctx != null &&
            (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable || sdk < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            callingUid != Process.SHELL_UID
        ) {
            Log.w(
                TAG,
                "event=debug_session_command_rejected uid=$callingUid sdk=$sdk " +
                    "debuggable=$debuggable",
            )
            return response("rejected", callingUid)
        }

        // Health probe: proves an authenticated Binder call reached the provider
        // without modifying any account or vault state.
        if (method == "probe") {
            Log.i(TAG, "event=debug_session_probe_ok uid=$callingUid")
            return response("ready", callingUid)
        }

        if (ctx == null) return response("no_context", callingUid)
        val deps = EntryPointAccessors.fromApplication(
            ctx.applicationContext,
            VaultKeyDebugSessionEntryPoint::class.java,
        )
        return when (method) {
            "lock" -> {
                val userId = deps.authRepository().userStateFlow.value?.activeUserId
                if (userId.isNullOrBlank()) {
                    Log.i(TAG, "event=debug_lock_no_active_account")
                    response("no_active_account", callingUid)
                } else {
                    // Real manager state transition, not a fabricated event.
                    deps.vaultLockManager().lockVault(userId, isUserInitiated = true)
                    Log.i(TAG, "event=debug_real_lock_requested")
                    response("lock_requested", callingUid)
                }
            }

            "switch_other" -> {
                val auth = deps.authRepository()
                val state = auth.userStateFlow.value
                if (state == null) {
                    Log.i(TAG, "event=debug_switch_no_active_account")
                    response("no_active_account", callingUid)
                } else {
                    val target = state.accounts.firstOrNull {
                        it.userId != state.activeUserId && it.isLoggedIn
                    }
                    if (target == null) {
                        Log.i(TAG, "event=debug_switch_no_other_logged_in_account")
                        response("no_other_logged_in_account", callingUid)
                    } else {
                        val outcome = auth.switchAccount(target.userId)
                        val result = when (outcome) {
                            SwitchAccountResult.AccountSwitched -> "switched"
                            SwitchAccountResult.NoChange -> "unchanged"
                        }
                        Log.i(TAG, "event=debug_real_account_switch result=$result")
                        response(result, callingUid)
                    }
                }
            }

            else -> {
                Log.w(TAG, "event=debug_unknown_session_command")
                response("unknown_method", callingUid)
            }
        }
    }

    // No data API: all query/mutation paths are explicitly unavailable.
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    private fun response(status: String, callerUid: Int): Bundle = Bundle().apply {
        putString("status", status)
        putInt("caller_uid", callerUid)
    }

    companion object {
        private const val TAG = "VaultKeyHID"
    }
}
