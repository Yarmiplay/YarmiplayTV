package com.yarmiplaytv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlsTest {
    @Test
    fun `mpv's internal protocols are refused whatever their case`() {
        listOf(
            "fdclose://3", "fd://0", "FDCLOSE://12", "edl://a.mkv,0,10", "av://v4l2:/dev/video0", "lavf://file:/x",
            "memory://abc", "slice://0-10@fdclose://3", "hex://00ff", "pipe://3", "mf://*.jpg",
        ).forEach { assertTrue(it, MediaUrls.isInternalProtocol(it)) }
    }

    @Test
    fun `streams, files and names are allowed`() {
        listOf(
            "http://host/video.mkv", "https://host/a.mp4?api_key=1", "rtsp://cam/live", "rtmp://x/y", "smb://nas/share/a.mkv",
            "content://media/external/video/1", "file:///sdcard/a.mkv", "Movie.2020.mkv", "/sdcard/a.mkv", "ytdl://abc",
            "relay://1/a.mkv", "C:\\Videos\\fd.mkv", "http://host/edl://x",
        ).forEach { assertFalse(it, MediaUrls.isInternalProtocol(it)) }
    }

    @Test
    fun `tokens in addresses are redacted`() {
        assertEquals(
            "Opening https://jf/Videos/1/stream?api_key=REDACTED&Static=true",
            MediaUrls.redact("Opening https://jf/Videos/1/stream?api_key=abc123&Static=true"),
        )
        assertEquals("http://plex/a?X-Plex-Token=REDACTED", MediaUrls.redact("http://plex/a?X-Plex-Token=zzz"))
        assertEquals("u?apikey=REDACTED#t", MediaUrls.redact("u?apikey=k#t"))
        assertEquals("u?a=1&access_token=REDACTED 'x'", MediaUrls.redact("u?a=1&access_token=tok 'x'"))
        assertEquals("u?ACCESS_TOKEN=REDACTED", MediaUrls.redact("u?ACCESS_TOKEN=tok"))
    }

    @Test
    fun `text without tokens is unchanged`() {
        val line = "Failed to open http://host/a.mkv?Static=true&page=2."
        assertEquals(line, MediaUrls.redact(line))
        assertEquals("plain", MediaUrls.redact("plain"))
    }
}
