package com.yarmiplaytv.ui.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Syncplay-style join screen's one-field server address, and the people table's file sizes. */
class ClassicFormatTest {
    @Test
    fun serverAddressesSplitIntoHostAndPort() {
        assertEquals("syncplay.pl" to 8999, parseServerAddress("syncplay.pl:8999"))
        assertEquals("syncplay.pl" to null, parseServerAddress(" syncplay.pl "))
        assertEquals("syncplay.pl" to null, parseServerAddress("syncplay.pl:"))
        assertEquals("::1" to 8995, parseServerAddress("[::1]:8995"))
        assertEquals("fe80::1" to null, parseServerAddress("fe80::1"))
    }

    @Test
    fun serverAddressesFormatBack() {
        assertEquals("syncplay.pl:8999", formatServerAddress("syncplay.pl", "8999"))
        assertEquals("[::1]:8999", formatServerAddress("::1", "8999"))
        assertEquals("syncplay.pl", formatServerAddress("syncplay.pl", ""))
    }

    @Test
    fun fileSizes() {
        assertEquals("192 MB", formatFileSize(192L * 1024 * 1024 + 5))
        assertEquals("1.5 GB", formatFileSize(3L * 512 * 1024 * 1024).replace(',', '.'))
        assertEquals("3 KB", formatFileSize(3L * 1024))
        assertEquals("", formatFileSize(0))
    }
}
