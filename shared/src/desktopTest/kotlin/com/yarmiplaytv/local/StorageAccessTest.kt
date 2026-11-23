package com.yarmiplaytv.local

import com.yarmiplaytv.media.subtitleSiblings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAccessTest {
    @Test
    fun `play build picks a folder and the website apk scans volumes`() {
        assertEquals(StorageAccess.FolderPicker, StorageAccessPolicy.forBuild(declaresAllFilesAccess = false))
        assertEquals(StorageAccess.AllFiles, StorageAccessPolicy.forBuild(declaresAllFilesAccess = true))
        assertEquals("Choose a folder or USB drive", StorageAccessPolicy.actionLabel(StorageAccess.FolderPicker))
        assertEquals("Scan USB and internal storage", StorageAccessPolicy.actionLabel(StorageAccess.AllFiles))
    }

    @Test
    fun `a device without a documents ui keeps a way in through the network`() {
        val note = StorageAccessPolicy.NO_DOCUMENTS_UI
        assertTrue(note.contains("FTP"))
        assertTrue(note.contains("SMB"))
        assertTrue(note.contains("Syncplay"))
        assertTrue(note.contains("no folder picker"))
    }

    @Test
    fun `tree labels name the folder or the usb volume`() {
        assertEquals("Movies", documentTreeLabel("primary:Movies"))
        assertEquals("Season 1", documentTreeLabel("primary:Shows/Season 1"))
        assertEquals("Internal storage", documentTreeLabel("primary:"))
        assertEquals("ABCD-1234", documentTreeLabel("ABCD-1234:"))
        assertEquals("primary", documentTreeLabel("primary"))
    }

    @Test
    fun `browsing a granted tree finds videos and keeps sidecars beside them`() {
        val tree = mapOf(
            "root" to listOf(
                dir("hidden", ".thumbnails"),
                dir("season", "Season 1"),
                file("notes", "notes.txt", "text/plain"),
                file("root-video", "Loose.mkv", "video/x-matroska", size = 50),
            ),
            "hidden" to listOf(file("secret", "Secret.mkv", "video/mp4")),
            "season" to listOf(
                file("ep", "Show.mkv", "video/x-matroska", size = 100, modified = 20),
                file("srt", "Show.en.srt", "application/x-subrip"),
                file("idx", "Show.idx", "application/octet-stream"),
                file("sub", "Show.sub", "application/octet-stream"),
                file("song", "Theme.mp3", "audio/mpeg", size = 8),
            ),
        )
        val found = browseSaf("root") { tree[it].orEmpty() }
        assertEquals(listOf("Loose.mkv", "Show.mkv", "Theme.mp3"), found.map { it.name })
        val episode = found.first { it.name == "Show.mkv" }
        assertEquals("Season 1", episode.directory)
        assertEquals(100L, episode.size)
        assertEquals(20L, episode.modified)
        val beside = tree.getValue("season").map { it.name }
        assertEquals(listOf("Show.en.srt", "Show.idx"), subtitleSiblings("Show.mkv", beside))
    }

    @Test
    fun `a hidden directory is not walked and the file cap is kept`() {
        assertNull(classifySafEntry(dir("x", ".cache"), ""))
        assertNull(classifySafEntry(SafEntry("x", "", SAF_DIRECTORY_MIME), ""))
        assertNull(classifySafEntry(file("r", "readme.txt", "text/plain"), ""))
        val tree = mapOf("root" to (1..5).map { file("v$it", "Clip $it.mp4", "video/mp4") })
        val found = browseSaf("root", childrenOf = { tree[it].orEmpty() }, maxFiles = 2)
        assertEquals(listOf("Clip 1.mp4", "Clip 2.mp4"), found.map { it.name })
    }

    private fun dir(id: String, name: String) = SafEntry(id, name, SAF_DIRECTORY_MIME)

    private fun file(id: String, name: String, mime: String, size: Long = 0, modified: Long = 0) =
        SafEntry(id, name, mime, size, modified)
}
