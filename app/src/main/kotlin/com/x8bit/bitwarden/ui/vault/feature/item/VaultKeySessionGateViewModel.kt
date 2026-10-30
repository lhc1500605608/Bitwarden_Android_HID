package com.x8bit.bitwarden.ui.vault.feature.item

import androidx.lifecycle.ViewModel
import com.bitwarden.vault.CipherRepromptType
import com.bitwarden.vault.CipherType
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.platform.manager.hid.HidOneShotVerification
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

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

    fun itemStateFlow(itemId: String) = vaultRepository.getVaultItemStateFlow(itemId)

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
        val cipher = vaultRepository.getVaultItemStateFlow(itemId).value.data ?: return false
        return cipher.id == itemId &&
            cipher.type == CipherType.LOGIN &&
            cipher.reprompt != CipherRepromptType.PASSWORD
    }

    fun isFieldAllowed(
        expectedUserId: String?,
        itemId: String,
        field: HidOneShotVerification.Field,
    ): Boolean {
        if (!isItemAvailable(expectedUserId, itemId)) return false
        val cipher = vaultRepository.getVaultItemStateFlow(itemId).value.data ?: return false
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
