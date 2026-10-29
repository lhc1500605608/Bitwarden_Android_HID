package com.x8bit.bitwarden.data.platform.manager.hid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HidOneShotVerificationTest {
    private val field = HidOneShotVerification.Field.USERNAME

    @Test
    fun `successful user verification grants exactly one use`() {
        val gate = HidOneShotVerification()
        gate.issue("item-1", "host-a", field, 100L)
        assertTrue(gate.consume("item-1", "host-a", field, 105L))
        assertFalse(gate.consume("item-1", "host-a", field, 106L))
    }

    @Test
    fun `grants cannot cross items hosts or credential fields`() {
        val gate = HidOneShotVerification()
        gate.issue("item-1", "host-a", field, 100L)
        assertFalse(gate.consume("item-2", "host-a", field, 101L))
        assertFalse(gate.consume("item-1", "host-a", field, 102L))

        gate.issue("item-1", "host-a", field, 100L)
        assertFalse(gate.consume("item-1", "host-b", field, 101L))

        gate.issue("item-1", "host-a", field, 100L)
        assertFalse(gate.consume(
            "item-1",
            "host-a",
            HidOneShotVerification.Field.PASSWORD,
            101L,
        ))
    }

    @Test
    fun `expired future and revoked grants are rejected`() {
        val gate = HidOneShotVerification(validityMs = 15_000L)
        gate.issue("item-1", "host-a", field, 100L)
        assertFalse(gate.consume("item-1", "host-a", field, 15_101L))

        gate.issue("item-1", "host-a", field, 100L)
        assertFalse(gate.consume("item-1", "host-a", field, 99L))

        gate.issue("item-1", "host-a", field, 100L)
        gate.invalidate()
        assertFalse(gate.consume("item-1", "host-a", field, 101L))
    }
}
