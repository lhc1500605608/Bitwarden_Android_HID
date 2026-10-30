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
    fun `combined login grant cannot authorize password or username separately`() {
        val gate = HidOneShotVerification()
        gate.issue(
            "item-1",
            "host-a",
            HidOneShotVerification.Field.LOGIN_COMBINED,
            100L,
        )
        assertFalse(gate.consume(
            "item-1",
            "host-a",
            HidOneShotVerification.Field.PASSWORD,
            101L,
        ))
        assertFalse(gate.consume(
            "item-1",
            "host-a",
            HidOneShotVerification.Field.LOGIN_COMBINED,
            102L,
        ))
        gate.issue(
            "item-1",
            "host-a",
            HidOneShotVerification.Field.LOGIN_COMBINED,
            103L,
        )
        assertTrue(gate.consume(
            "item-1",
            "host-a",
            HidOneShotVerification.Field.LOGIN_COMBINED,
            104L,
        ))
    }

    @Test
    fun `default verification grant accepts 30 seconds but refuses 30 seconds plus one ms`() {
        val gate = HidOneShotVerification()
        gate.issue("item-1", "host-a", field, 100L)
        assertTrue(gate.consume("item-1", "host-a", field, 30_100L))

        gate.issue("item-1", "host-a", field, 100L)
        assertFalse(gate.consume("item-1", "host-a", field, 30_101L))
        assertFalse(gate.consume("item-1", "host-a", field, 30_102L))
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
