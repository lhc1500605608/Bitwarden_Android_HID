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
    fun `rejects non US keyboard characters without partial result`() {
        assertNull(UsKeyboardReport.encode("abc中文"))
        assertNull(UsKeyboardReport.encode("aé"))
    }
}
