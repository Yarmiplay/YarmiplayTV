package com.yarmiplaytv.media

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress

class LanHostsTest {
    @After
    fun reset() {
        LanHosts.clear()
        LanHosts.probe = null
    }

    @Test
    fun `a name goes to its local address while that answers`() {
        var answers = true
        LanHosts.probe = { address, port -> address == "192.168.1.20" && port == 8920 && answers }
        LanHosts.add("MyName.DuckDNS.org", "192.168.1.20", 8920)
        assertEquals(listOf(InetAddress.getByName("192.168.1.20")), LanHosts.lookup("myname.duckdns.org"))
        assertNull(LanHosts.lookup("other.example"))
        assertEquals("192.168.1.20", LanHosts.hintFor("https://myname.duckdns.org:8920/web"))

        answers = false
        LanHosts.clear()
        LanHosts.add("myname.duckdns.org", "192.168.1.20", 8920)
        assertNull("away from home the name resolves as usual", LanHosts.lookup("myname.duckdns.org"))
    }

    @Test
    fun `the player is given the local address once it answered`() {
        val stream = "https://myname.duckdns.org:8920/Videos/abc/stream?static=true&api_key=k"
        LanHosts.probe = { _, _ -> true }
        LanHosts.add("myname.duckdns.org", "192.168.1.20", 8920)
        assertEquals("not checked yet", stream, LanHosts.direct(stream))
        LanHosts.lookup("myname.duckdns.org")
        assertEquals("https://192.168.1.20:8920/Videos/abc/stream?static=true&api_key=k", LanHosts.direct(stream))
        assertEquals("http://other.example:8096/x", LanHosts.direct("http://other.example:8096/x"))
        assertEquals("content://media/1", LanHosts.direct("content://media/1"))
    }

    @Test
    fun `IP addresses are never hinted names`() {
        LanHosts.add("192.168.1.20", "192.168.1.21", 8096)
        assertNull(LanHosts.hintFor("http://192.168.1.20:8096"))
    }

    @Test
    fun `only addresses inside a home network count as local`() {
        assertTrue(LanHosts.isLocalAddress("10.100.102.10"))
        assertTrue(LanHosts.isLocalAddress("192.168.1.20"))
        assertTrue(LanHosts.isLocalAddress("fd12:3456::20"))
        assertTrue(LanHosts.isLocalAddress("[fe80::1]"))
        assertFalse(LanHosts.isLocalAddress("46.116.126.210"))
        assertFalse(LanHosts.isLocalAddress("2a02:6b8::20"))
        assertFalse(LanHosts.isLocalAddress("myname.duckdns.org"))
    }

    @Test
    fun `plain HTTP and names are kept as they are`() = runBlocking {
        assertEquals("http://192.168.1.20:8096", LanHosts.byCertificateName("http://192.168.1.20:8096"))
        assertEquals("https://jellyfin.example:8920", LanHosts.byCertificateName("https://jellyfin.example:8920"))
    }

    /** LAN_TLS_SERVER=10.100.102.10:8920 checks a real server whose certificate is for a name. */
    @Test
    fun `a real server found by its address is used under its certificate's name`() = runBlocking {
        val server = System.getenv("LAN_TLS_SERVER").orEmpty()
        assumeTrue(server.isNotEmpty())
        val url = LanHosts.byCertificateName("https://$server")
        println("https://$server -> $url")
        assertFalse(url.contains(server.substringBefore(':')))
        assertEquals(server.substringBefore(':'), LanHosts.hintFor(url))
    }
}
