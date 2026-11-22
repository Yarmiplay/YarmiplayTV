package com.yarmiplaytv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class IntentMediaTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `only network streams play from the url extra`() {
        listOf("http://host/a.mkv", "HTTPS://host/a.mp4", "rtsp://cam/1", "rtmp://x/live", "rtmps://x/live")
            .forEach { assertTrue(it, IntentMedia.isStreamUrl(it)) }
        listOf(
            "fdclose://3", "fd://0", "edl://a", "memory://x", "lavf://file:/a", "av://v4l2:/dev/video0",
            "file:///data/user/0/com.yarmiplaytv/files/datastore/settings.preferences_pb", "content://x/1",
            "/sdcard/a.mkv", "javascript:alert(1)", "smb://nas/a.mkv", "",
        ).forEach { assertFalse(it, IntentMedia.isStreamUrl(it)) }
    }

    @Test
    fun `files in the app's own storage are private`() {
        val data = tmp.newFolder("data", "user", "0", "com.yarmiplaytv")
        val settings = File(data, "files/datastore/settings.preferences_pb").apply { parentFile.mkdirs(); writeText("x") }
        val dirs = listOf(data.path)
        assertTrue(IntentMedia.isPrivateFile(settings.path, dirs))
        assertTrue(IntentMedia.isPrivateFile(File(data, "cache/a.mkv").path, dirs))
        assertTrue(IntentMedia.isPrivateFile(data.path, dirs))
        // `..` from outside leads back in.
        val outside = tmp.newFolder("sdcard")
        assertTrue(IntentMedia.isPrivateFile(File(outside, "../data/user/0/com.yarmiplaytv/files/datastore/settings.preferences_pb").path, dirs))
        assertTrue(IntentMedia.isPrivateFile(null, dirs))
        assertTrue(IntentMedia.isPrivateFile("/proc/self/environ", dirs))
    }

    @Test
    fun `a link into the app's storage is private`() {
        val data = tmp.newFolder("app")
        val secret = File(data, "secret").apply { writeText("x") }
        val link = File(tmp.root, "link")
        val linked = runCatching { Files.createSymbolicLink(link.toPath(), data.toPath()) }.isSuccess
        if (linked) assertTrue(IntentMedia.isPrivateFile(File(link, secret.name).path, listOf(data.path)))
    }

    @Test
    fun `other files are not private`() {
        val data = tmp.newFolder("data", "user", "0", "com.yarmiplaytv")
        val video = File(tmp.newFolder("sdcard", "Movies"), "a.mkv").apply { writeText("x") }
        assertFalse(IntentMedia.isPrivateFile(video.path, listOf(data.path)))
        // A sibling whose name only starts the same is outside.
        val sibling = File(data.parentFile, "com.yarmiplaytv.other/a.mkv").apply { parentFile.mkdirs(); writeText("x") }
        assertFalse(IntentMedia.isPrivateFile(sibling.path, listOf(data.path)))
    }
}
