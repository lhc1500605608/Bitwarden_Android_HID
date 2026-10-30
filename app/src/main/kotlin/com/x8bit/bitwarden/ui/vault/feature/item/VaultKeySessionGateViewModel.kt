package com.x8bit.bitwarden.ui.vault.feature.item

import androidx.lifecycle.ViewModel
import com.bitwarden.core.data.repository.model.DataState
import com.bitwarden.vault.CipherView
import com.bitwarden.vault.CipherRepromptType
import com.bitwarden.vault.CipherType
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.ValidatePasswordResult
import com.x8bit.bitwarden.data.platform.manager.hid.HidOneShotVerification
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.StateFlow

/**
 * Session and current-item authorization fence for the DEBUG HID prototype.
 *
 * Do not infer an unlocked vault from a visible Compose screen or an earlier
 * successful system biometric prompt: every key must still be authorized
 * against the current active account, vault lock manager and live cipher.
 */
@HiltViewModel
internal class VaultKeySessionGateViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val vaultLockManager: VaultLockManager,
    private val vaultRepository: VaultRepository,
) : ViewModel() {
    val userStateFlow = authRepository.userStateFlow
    val vaultStateEventFlow = vaultLockManager.vaultStateEventFlow

    // VaultRepositoryImpl starts a NEW Lazily subscribed stateIn flow on every
    // getVaultItemStateFlow() call. Recreating it for each key would always
    // read initial Loading and falsely revoke an otherwise valid send.
    private val itemFlows = ConcurrentHashMap<String, StateFlow<DataState<CipherView?>>>()

    fun itemStateFlow(itemId: String): StateFlow<DataState<CipherView?>> =
        itemFlows.computeIfAbsent(itemId) {
            vaultRepository.getVaultItemStateFlow(it)
        }

    fun captureActiveUserId(): String? {
        val userId = authRepository.userStateFlow.value?.activeUserId ?: return null
        return userId.takeIf { isVaultSessionValid(it) }
    }

    fun isVaultSessionValid(expectedUserId: String?): Boolean {
        if (expectedUserId.isNullOrBlank()) return false
        val state = authRepository.userStateFlow.value ?: return false
        if (state.activeUserId != expectedUserId) return false
        val account = state.activeAccount
        return account.userId == expectedUserId &&
            account.isLoggedIn &&
            account.isVaultUnlocked &&
            vaultLockManager.isVaultUnlocked(expectedUserId)
    }

    fun isItemAvailable(expectedUserId: String?, itemId: String): Boolean {
        if (!isVaultSessionValid(expectedUserId)) return false
        val cipher = itemStateFlow(itemId).value.data ?: return false
        return cipher.id == itemId &&
            cipher.type == CipherType.LOGIN
    }

    fun requiresMasterPasswordReprompt(
        expectedUserId: String?,
        itemId: String,
    ): Boolean? {
        if (!isItemAvailable(expectedUserId, itemId)) return null
        return itemStateFlow(itemId).value.data?.reprompt == CipherRepromptType.PASSWORD
    }

    /**
     * Uses Bitwarden's same repository validation as the listing screen.
     * The plaintext master password lives only in the caller's transient
     * dialog callback and coroutine; no password or success flag is persisted.
     */
    suspend fun verifyMasterPasswordForHid(
        expectedUserId: String?,
        itemId: String,
        masterPassword: String,
    ): Boolean {
        if (masterPassword.isEmpty() ||
            requiresMasterPasswordReprompt(expectedUserId, itemId) != true ||
            authRepository.userStateFlow.value?.activeAccount?.hasMasterPassword != true
        ) return false
        val outcome = authRepository.validatePassword(masterPassword)
        return outcome is ValidatePasswordResult.Success && outcome.isValid &&
            requiresMasterPasswordReprompt(expectedUserId, itemId) == true
    }

    fun isFieldAllowed(
        expectedUserId: String?,
        itemId: String,
        field: HidOneShotVerification.Field,
        protectedItemRepromptVerified: Boolean = false,
    ): Boolean {
        if (!isItemAvailable(expectedUserId, itemId)) return false
        val cipher = itemStateFlow(itemId).value.data ?: return false
        if (cipher.reprompt == CipherRepromptType.PASSWORD &&
            !protectedItemRepromptVerified
        ) return false
        val login = cipher.login ?: return false
        val account = authRepository.userStateFlow.value?.activeAccount ?: return false
        return when (field) {
            HidOneShotVerification.Field.USERNAME -> !login.username.isNullOrEmpty()
            HidOneShotVerification.Field.PASSWORD,
            HidOneShotVerification.Field.LOGIN_COMBINED,
                -> cipher.viewPassword && !login.password.isNullOrEmpty() &&
                    (field != HidOneShotVerification.Field.LOGIN_COMBINED ||
                        !login.username.isNullOrEmpty())
            HidOneShotVerification.Field.TOTP ->
                (account.isPremium || cipher.organizationUseTotp) &&
                    !login.totp.isNullOrEmpty()
        }
    }
}
