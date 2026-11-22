package com.yarmiplaytv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RedactTest {
    @Test
    fun takesTokensOutOfUrls() {
        assertEquals(
            "Can't open http://h:8096/Videos/1/stream?static=true&api_key=REDACTED&mediaSourceId=2",
            Redact.tokens("Can't open http://h:8096/Videos/1/stream?static=true&api_key=abc123&mediaSourceId=2"),
        )
        assertEquals(
            "\"https://p:32400/library/parts/7/file.mkv?X-Plex-Token=REDACTED\" failed",
            Redact.tokens("\"https://p:32400/library/parts/7/file.mkv?X-Plex-Token=xyz-789\" failed"),
        )
        assertEquals("nothing secret here", Redact.tokens("nothing secret here"))
        assertEquals("", Redact.tokens(null as String?))
    }

    @Test
    fun takesTokensOutOfAnErrorsMessagesOnly() {
        val plain = IOException("timed out")
        assertSame(plain, Redact.tokens(plain))

        val leaky = IllegalStateException("open failed", IOException("Could not open input \"http://h/a?api_key=secret\""))
        val safe = Redact.tokens(leaky)
        val chain = generateSequence(safe) { it.cause }.toList()
        assertEquals(2, chain.size)
        assertTrue(chain.none { "secret" in it.toString() })
        assertTrue(chain[1].toString().startsWith("java.io.IOException: "))
        assertEquals(leaky.stackTrace.toList(), safe.stackTrace.toList())
        assertFalse("secret" in safe.stackTraceToString())
    }
}
