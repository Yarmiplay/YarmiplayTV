package com.yarmiplaytv.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PicturesFolderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dirsFile(vararg lines: String) = tmp.newFile("user-dirs.dirs").apply { writeText(lines.joinToString("\n")) }

    @Test
    fun linuxUsesXdgPicturesDir() {
        val config = tmp.newFolder("config")
        File(config, "user-dirs.dirs").writeText("# comment\nXDG_VIDEOS_DIR=\"\$HOME/Video's\"\nXDG_PICTURES_DIR=\"\$HOME/Afbeeldingen\"\n")
        val folder = PicturesFolder.resolve("Linux", env = { if (it == "XDG_CONFIG_HOME") config.path else null }, home = "/home/sam")
        assertEquals(File("/home/sam/Afbeeldingen"), folder)
    }

    @Test
    fun linuxFallsBackToPicturesInHome() {
        assertNull(PicturesFolder.xdgPictures(File(tmp.root, "missing"), "/home/sam"))
        assertNull(PicturesFolder.xdgPictures(dirsFile("XDG_PICTURES_DIR=\"\$HOME/\""), "/home/sam"))
        val folder = PicturesFolder.resolve("Linux", env = { null }, home = tmp.root.path)
        assertEquals(File(tmp.root, "Pictures"), folder)
    }

    @Test
    fun macUsesPicturesInHome() {
        assertEquals(File("/Users/sam/Pictures"), PicturesFolder.resolve("Mac OS X", env = { null }, home = "/Users/sam"))
    }

    @Test
    fun windowsAsksForTheKnownFolder() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val folder = PicturesFolder.resolve()
        assertTrue("$folder", folder.isAbsolute && folder.isDirectory)
    }
}
