package com.yarmiplaytv.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WindowsSetupTest {
    @get:Rule val tmp = TemporaryFolder()

    private val empty = DiscoveredInstall(null, false, false, false, emptyList(), false)

    @Test
    fun `a fresh install ticks the shortcuts and video files`() {
        val choices = choicesFor(empty, emptyArray(), File("C:/Users/Sam/AppData/Local/YarmiplayTV"), unattended = false)
        assertEquals("C:/Users/Sam/AppData/Local/YarmiplayTV", choices.directory.path.replace('\\', '/'))
        assertTrue(choices.desktopShortcut)
        assertTrue(choices.startMenuShortcut)
        assertTrue(choices.videoFiles)
        assertTrue(choices.launchWhenFinished)
    }

    @Test
    fun `an upgrade keeps the shortcuts that are already there`() {
        val found = DiscoveredInstall(
            directory = File("D:/Apps/YarmiplayTV"),
            desktopShortcut = true,
            startMenuShortcut = false,
            videoFiles = false,
            msiProductCodes = emptyList(),
            managedByUs = true,
        )
        val choices = choicesFor(found, emptyArray(), File("C:/default"), unattended = false)
        assertEquals("D:/Apps/YarmiplayTV", choices.directory.path.replace('\\', '/'))
        assertTrue(choices.desktopShortcut)
        assertFalse(choices.startMenuShortcut)
        assertFalse("a previous branded install with videos off stays off", choices.videoFiles)
    }

    @Test
    fun `an msi upgrade keeps video files and the silent update uses the given folder`() {
        val msi = DiscoveredInstall(
            directory = File("C:/Users/Sam/AppData/Local/YarmiplayTV"),
            desktopShortcut = true,
            startMenuShortcut = true,
            videoFiles = false,
            msiProductCodes = listOf("{6F0C2B1E-4E59-4C1B-9B7E-2D1A5C3F8E41}"),
            managedByUs = false,
        )
        val choices = choicesFor(
            msi,
            arrayOf("--silent", "--dir", "C:/Keep/YarmiplayTV"),
            File("C:/default"),
            unattended = true,
        )
        assertEquals("C:/Keep/YarmiplayTV", choices.directory.path.replace('\\', '/'))
        assertTrue(choices.videoFiles)
        assertFalse(choices.launchWhenFinished)
        assertTrue(choicesFor(msi, arrayOf("--relaunch"), File("C:/default"), unattended = true).launchWhenFinished)
    }

    @Test
    fun `the probe names the install folder, shortcuts and msi product`() {
        val probe = parseProbe(
            """
            desktop=C:\Users\Sam\Desktop
            programs=C:\Users\Sam\AppData\Roaming\Microsoft\Windows\Start Menu\Programs
            local=C:\Users\Sam\AppData\Local
            desktopLnk=1
            startLnk=0
            ours=C:\Users\Sam\AppData\Local\YarmiplayTV
            """.trimIndent() + "\nmsi={AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE}\tC:\\Old\nassoc=0\n",
        )
        assertEquals("C:/Users/Sam/AppData/Local/YarmiplayTV", slash(probe.discovered.directory!!))
        assertTrue(probe.discovered.managedByUs)
        assertTrue(probe.discovered.desktopShortcut)
        assertFalse(probe.discovered.startMenuShortcut)
        assertEquals(listOf("{AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE}"), probe.discovered.msiProductCodes)
        assertEquals("C:/Users/Sam/Desktop/YarmiplayTV.lnk", slash(probe.paths.desktopShortcut))
        assertEquals("C:/Users/Sam/AppData/Local/YarmiplayTV", slash(probe.paths.defaultDir))
    }

    @Test
    fun `copying the image replaces stale files and reports progress`() {
        val source = tmp.newFolder("image")
        File(source, "YarmiplayTV.exe").writeText("new")
        File(source, "app").mkdirs()
        File(source, "app/app.jar").writeText("jar")
        val dest = tmp.newFolder("dest")
        File(dest, "old.dll").writeText("stale")
        val seen = mutableListOf<Float>()
        copyAppImage(source, dest) { seen += it }
        assertEquals("new", File(dest, "YarmiplayTV.exe").readText())
        assertEquals("jar", File(dest, "app/app.jar").readText())
        assertFalse(File(dest, "old.dll").exists())
        assertEquals(1f, seen.last())
    }

    @Test
    fun `install refuses to copy the package onto itself`() {
        val source = tmp.newFolder("pack")
        File(source, "YarmiplayTV.exe").writeText("x")
        assertThrows(IllegalStateException::class.java) {
            assertInstallable(source, source)
        }
        assertThrows(IllegalStateException::class.java) {
            assertInstallable(source, File(source, "nested"))
        }
    }

    @Test
    fun `the setup footer is a little-endian length and the magic`() {
        val out = ByteArrayOutputStream()
        writeSetupFooter(out, 300L)
        val bytes = out.toByteArray()
        assertEquals(16, bytes.size)
        assertEquals(300L, ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long)
        assertEquals(SETUP_PAYLOAD_MAGIC, bytes.copyOfRange(8, 16).toString(Charsets.US_ASCII))
    }

    @Test
    fun `install and removal scripts name the shortcuts, videos and uninstall entry`() {
        val exe = File("C:/Apps/YarmiplayTV/YarmiplayTV.exe")
        val dir = exe.parentFile
        val choices = SetupChoices(dir, desktopShortcut = true, startMenuShortcut = false, videoFiles = true, launchWhenFinished = true)
        val desktop = File("C:/Users/Sam/Desktop/YarmiplayTV.lnk")
        val start = File("C:/Users/Sam/AppData/Roaming/Microsoft/Windows/Start Menu/Programs/YarmiplayTV/YarmiplayTV.lnk")
        val script = installScript(exe, dir, "2.7.4", 12000, choices, desktop, start)
        assertTrue(script.contains("YarmiplayTV.exe"))
        assertTrue(script.contains("YarmiplayTV.video"))
        assertTrue(script.contains("--setup --uninstall"))
        assertTrue(script.contains("DisplayVersion"))
        assertTrue(script.contains("2.7.4"))
        assertTrue(script.contains("Remove-Item"))
        val removal = removalScript(desktop, start)
        assertTrue(removal.contains("Uninstall\\YarmiplayTV") || removal.contains("Uninstall\\\\YarmiplayTV") || removal.contains("YarmiplayTV"))
        assertFalse(removal.contains("Set-Link"))
        assertTrue(probeScript().contains("DisplayName"))
        assertTrue(probeScript().contains("YarmiplayTV.lnk"))
    }

    private fun slash(file: File) = file.path.replace('\\', '/')

    @Test
    fun `the setup scripts parse in powershell`() {
        if (!System.getProperty("os.name").lowercase().startsWith("windows")) return
        val exe = File("C:/Apps/Yarmiplay's TV/YarmiplayTV.exe")
        val choices = SetupChoices(exe.parentFile, true, true, true, false)
        val scripts = listOf(
            probeScript(),
            installScript(exe, exe.parentFile, "2.7.4", 10, choices, File("C:/Users/Sam/Desktop/YarmiplayTV.lnk"), File("C:/Start/YarmiplayTV.lnk")),
            removalScript(File("C:/Users/Sam/Desktop/YarmiplayTV.lnk"), File("C:/Start/YarmiplayTV.lnk")),
            deleteAfterExitScript(exe.parentFile, listOf(4, 5)),
        )
        scripts.forEach { script ->
            val file = tmp.newFile("script.txt").apply { writeText(script) }
            val check = "\$e = \$null; [void][System.Management.Automation.Language.Parser]::ParseInput((Get-Content -Raw -LiteralPath '${file.path}'), [ref]\$null, [ref]\$e); \$e.Count"
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", check)
                .redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            assertEquals("parse errors in:\n$script\n$out", "0", out)
            file.delete()
        }
    }
}
