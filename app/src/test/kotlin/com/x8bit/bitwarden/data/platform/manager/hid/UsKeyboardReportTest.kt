package com.x8bit.bitwarden.data.platform.manager.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsKeyboardReportTest {
    @Test
    fun `encodes lowercase uppercase and number keys`() {
        val reports = requireNotNull(UsKeyboardReport.encode("aA1"))
        assertEquals(3, reports.size)
        assertArrayEquals(byteArrayOf(0, 0, 4, 0, 0, 0, 0, 0), reports[0])
        assertArrayEquals(byteArrayOf(2, 0, 4, 0, 0, 0, 0, 0), reports[1])
        assertArrayEquals(byteArrayOf(0, 0, 0x1E, 0, 0, 0, 0, 0), reports[2])
    }

    @Test
    fun `encodes symbols and tab`() {
        val reports = requireNotNull(UsKeyboardReport.encode("!@\t"))
        assertEquals(3, reports.size)
        assertArrayEquals(byteArrayOf(2, 0, 0x1E, 0, 0, 0, 0, 0), reports[0])
        assertArrayEquals(byteArrayOf(2, 0, 0x1F, 0, 0, 0, 0, 0), reports[1])
        assertArrayEquals(byteArrayOf(0, 0, 0x2B, 0, 0, 0, 0, 0), reports[2])
    }

    @Test
    fun `baseline and ampersand test strings use exact expected report counts`() {
        val baseline = "VaultKey-Test Aa09 !@#-_=+"
        val extended = baseline + "&"
        assertEquals(26, baseline.toByteArray(Charsets.US_ASCII).size)
        assertEquals(27, extended.toByteArray(Charsets.US_ASCII).size)

        val baseReports = requireNotNull(UsKeyboardReport.encode(baseline))
        val extendedReports = requireNotNull(UsKeyboardReport.encode(extended))
        assertEquals(26, baseReports.size)
        assertEquals(27, extendedReports.size)
        // US HID usage 0x24 is the 7/& key; Left Shift changes it to '&'.
        assertArrayEquals(
            byteArrayOf(2, 0, 0x24, 0, 0, 0, 0, 0),
            extendedReports.last(),
        )
    }

    @Test
    fun `combined login encodes username tab password in one ordered report stream`() {
        val testUsername = "admin"
        val testPassword = "FakeTest-Pass@23#"
        val encoded = requireNotNull(
            UsKeyboardReport.encode("${testUsername}\t${testPassword}"),
        )
        assertEquals(
            testUsername.length + 1 + testPassword.length,
            encoded.size,
        )
        // Explicit HID Tab usage (0x2B) between the two inputs, not an
        // ASCII 0x09 byte transmitted literally.
        assertArrayEquals(
            byteArrayOf(0, 0, 0x2B, 0, 0, 0, 0, 0),
            encoded[testUsername.length],
        )
        assertArrayEquals(
            byteArrayOf(0, 0, 0x04, 0, 0, 0, 0, 0),
            encoded[0],
        )
        assertArrayEquals(
            byteArrayOf(2, 0, 0x09, 0, 0, 0, 0, 0),
            encoded[testUsername.length + 1],
        )
        // All-or-nothing mapping: unsupported code points cause no
        // transmission, including a valid username prefix.
        assertNull(UsKeyboardReport.encode("admin\tbad密碼"))
    }

    @Test
    fun `rejects non US keyboard characters without partial result`() {
        assertNull(UsKeyboardReport.encode("abc中文"))
        assertNull(UsKeyboardReport.encode("aé"))
    }
}
