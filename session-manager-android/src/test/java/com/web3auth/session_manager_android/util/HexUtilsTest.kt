package com.web3auth.session_manager_android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HexUtilsTest {

    @Test
    fun isHexString_acceptsPrefixedAndUnprefixed() {
        assertTrue(HexUtils.isHexString("0xabc123"))
        assertTrue(HexUtils.isHexString("ABC123"))
        assertFalse(HexUtils.isHexString(""))
        assertFalse(HexUtils.isHexString("0xzz"))
        assertFalse(HexUtils.isHexString("hello"))
    }

    @Test
    fun padHexString_padsAndTruncatesTo64() {
        assertEquals("00000000000000000000000000000000000000000000000000000000000000ab", HexUtils.padHexString("ab"))
        assertEquals("ab", HexUtils.padHexString("0xab").takeLast(2))
        val longHex = "11".repeat(40)
        assertEquals(64, HexUtils.padHexString(longHex).length)
    }

    @Test
    fun add0x_and_remove0x_areSymmetric() {
        assertEquals("0xdead", HexUtils.add0x("dead"))
        assertEquals("0xdead", HexUtils.add0x("0xdead"))
        assertEquals("dead", HexUtils.remove0x("0xdead"))
        assertEquals("dead", HexUtils.remove0x("dead"))
    }
}
