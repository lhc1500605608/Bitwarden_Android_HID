package com.x8bit.bitwarden.data.platform.manager.hid

/**
 * Single-use, short-lived user-verification grant for one exact vault operation.
 *
 * This stores only item ID, receiving host ID and field type, not a credential,
 * OTP, PIN, token or biometric result. A successful platform verification is
 * the ONLY call site permitted to issue a grant.
 *
 * No wall-clock timestamps: callers use Android's elapsedRealtime clock.
 */
internal class HidOneShotVerification(
    private val validityMs: Long = 30_000L,
) {
    enum class Field { USERNAME, PASSWORD, TOTP, LOGIN_COMBINED }

    private data class Grant(
        val itemId: String,
        val hostAddress: String,
        val field: Field,
        val verifiedAtElapsedMs: Long,
    )

    private var grant: Grant? = null

    fun issue(
        itemId: String,
        hostAddress: String,
        field: Field,
        nowElapsedMs: Long,
    ) {
        require(itemId.isNotBlank())
        require(hostAddress.isNotBlank())
        grant = Grant(itemId, hostAddress, field, nowElapsedMs)
    }

    /**
     * Regardless of result, the grant is consumed (and erased) on the first attempt.
     * Neither an old grant nor one for another item/host/field is transferable.
     */
    fun consume(
        itemId: String,
        hostAddress: String,
        field: Field,
        nowElapsedMs: Long,
    ): Boolean {
        val current = grant
        grant = null
        return current != null &&
            current.itemId == itemId &&
            current.hostAddress == hostAddress &&
            current.field == field &&
            nowElapsedMs >= current.verifiedAtElapsedMs &&
            nowElapsedMs - current.verifiedAtElapsedMs <= validityMs
    }

    fun invalidate() {
        grant = null
    }
}
