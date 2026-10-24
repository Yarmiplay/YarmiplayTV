package com.yarmiplaytv.desktop

import com.yarmiplaytv.update.Update
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class UpdateSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val noEnv: (String) -> String? = { null }
    private val exe = File("C:/Users/Sam/AppData/Local/YarmiplayTV/YarmiplayTV.exe")
    private val macLauncher = File("/Applications/YarmiplayTV.app/Contents/MacOS/YarmiplayTV")
    private val linuxLauncher = File("/opt/yarmiplaytv/bin/YarmiplayTV")

    private fun kind(
        os: String,
        store: String? = null,
        env: (String) -> String? = noEnv,
        launcher: File? = null,
        portable: Boolean = false,
        dpkg: Boolean = false,
    ) = installKind(os, store, env, launcher, portable) { dpkg }

    @Test
    fun `tells how the app was installed`() {
        assertEquals(InstallKind.MSI, kind("Windows 11", launcher = exe))
        assertEquals(InstallKind.PORTABLE, kind("Windows 11", launcher = exe, portable = true))
        assertEquals(InstallKind.MS_STORE, kind("Windows 11", store = "msstore", launcher = exe))
        assertEquals(InstallKind.DMG, kind("Mac OS X", launcher = macLauncher))
        assertEquals(InstallKind.DEB, kind("Linux", launcher = linuxLauncher, dpkg = true))
        assertEquals("an .rpm or AppImage", InstallKind.LINUX_OTHER, kind("Linux", launcher = linuxLauncher, dpkg = false))
        assertEquals(InstallKind.AUR, kind("Linux", store = "aur", launcher = linuxLauncher, dpkg = true))
        assertEquals(InstallKind.FLATPAK, kind("Linux", env = mapOf("FLATPAK_ID" to "com.yarmiplay.TV")::get))
        assertEquals(InstallKind.SNAP, kind("Linux", env = mapOf("SNAP" to "/snap/yarmiplaytv/12")::get))
        assertEquals(InstallKind.OTHER_STORE, kind("Linux", store = "something"))
        assertEquals("run from a build", InstallKind.WINDOWS_OTHER, kind("Windows 11"))
        assertEquals(InstallKind.MAC_OTHER, kind("Mac OS X", launcher = File("/usr/local/bin/YarmiplayTV")))
    }

    @Test
    fun `download page installs pick their package and installer`() {
        fun source(kind: InstallKind, launcher: File) = updateSource(kind, launcher) as UpdateSource.DownloadPage
        assertEquals("windows", source(InstallKind.MSI, exe).platform)
        assertTrue(source(InstallKind.MSI, exe).installer is WindowsUpdater)
        assertEquals("windows-portable", source(InstallKind.PORTABLE, exe).platform)
        assertTrue(source(InstallKind.PORTABLE, exe).installer is PortableUpdater)
        assertEquals("macos", source(InstallKind.DMG, macLauncher).platform)
        assertTrue(source(InstallKind.DMG, macLauncher).installer is MacUpdater)
        assertEquals("linux", source(InstallKind.DEB, linuxLauncher).platform)
        assertTrue(source(InstallKind.DEB, linuxLauncher).installer is DebUpdater)
        assertEquals("linux", source(InstallKind.LINUX_OTHER, linuxLauncher).platform)
        assertNull("only links to the page", source(InstallKind.LINUX_OTHER, linuxLauncher).installer)
        assertNull((updateSource(InstallKind.WINDOWS_OTHER, null) as UpdateSource.DownloadPage).installer)
    }

    @Test
    fun `stores update the app themselves`() {
        fun store(kind: InstallKind) = (updateSource(kind, null) as UpdateSource.Store).store
        assertEquals("ms-windows-store://pdp/?productid=9PDBVR6W069J", store(InstallKind.MS_STORE).pageUrl)
        assertNull(store(InstallKind.MS_STORE).command)
        assertEquals("flatpak update com.yarmiplay.TV", store(InstallKind.FLATPAK).command)
        assertEquals("appstream://com.yarmiplay.TV", store(InstallKind.FLATPAK).pageUrl)
        assertEquals("snap refresh yarmiplaytv", store(InstallKind.SNAP).command)
        assertEquals("yay -S yarmiplaytv-bin", store(InstallKind.AUR).command)
        assertEquals("https://aur.archlinux.org/packages/yarmiplaytv-bin", store(InstallKind.AUR).pageUrl)
    }

    @Test
    fun `the portable update keeps the settings and starts the app again`() {
        val launcher = File("C:/Apps/YarmiplayTV/YarmiplayTV.exe")
        val script = PortableUpdater.script(File("C:/Temp/YarmiplayTV-1.9.0-portable.zip"), launcher, listOf("--room", "it's mine"), "1.9.0", listOf(10, 20), relaunch = true)
        assertTrue(script.startsWith("Wait-Process -Id 10,20 "))
        assertTrue(script.contains("Expand-Archive -LiteralPath 'C:\\Temp\\YarmiplayTV-1.9.0-portable.zip'") || script.contains("Expand-Archive -LiteralPath 'C:/Temp/YarmiplayTV-1.9.0-portable.zip'"))
        assertTrue("data stays", script.contains("Where-Object { \$_.Name -ne 'data' }"))
        assertTrue("only the app's folders are swapped", script.contains("foreach (\$n in 'app','runtime')"))
        assertTrue("put back on failure", script.contains("Move-Item -LiteralPath (\$d + '.old') -Destination \$d"))
        assertTrue(script.contains("\$env:YARMIPLAYTV_UPDATE_FAILED = '1.9.0'"))
        assertTrue(script.endsWith("-ArgumentList '--room \"it''s mine\"'"))
        assertFalse(PortableUpdater.script(File("x.zip"), launcher, emptyList(), "1.9.0", listOf(1), relaunch = false).contains("Start-Process"))
    }

    @Test
    fun `the mac update mounts the image, swaps the bundle and opens it`() {
        val launcher = File("/Applications/Yarmiplay's TV.app/Contents/MacOS/YarmiplayTV")
        val script = MacUpdater.script(
            File("/tmp/YarmiplayTV-1.9.0.dmg"), launcher, File("/tmp/swap.sh"), listOf("--file", "a b.mkv"), "1.9.0", pid = 42, relaunch = true,
        )
        val lines = script.lines()
        assertEquals("while kill -0 42 2>/dev/null; do sleep 0.5; done", lines[0])
        assertTrue(script.contains("hdiutil attach -nobrowse -readonly -noautoopen -mountpoint \"\$mnt\" '/tmp/YarmiplayTV-1.9.0.dmg'"))
        val app = "'/Applications/Yarmiplay'\\''s TV.app'"
        assertTrue(script.contains("/bin/sh '/tmp/swap.sh' \"\$src\" $app && ok=1"))
        assertTrue("asks for an administrator when it can't write", script.contains("with administrator privileges"))
        assertTrue(script.contains("hdiutil detach \"\$mnt\""))
        assertTrue(script.contains("open -n $app --args '--file' 'a b.mkv'"))
        assertTrue(script.contains("YARMIPLAYTV_UPDATE_FAILED='1.9.0' nohup '/Applications/Yarmiplay'\\''s TV.app/Contents/MacOS/YarmiplayTV' '--file' 'a b.mkv' >/dev/null 2>&1 &"))
        assertTrue(script.indexOf("hdiutil attach") < script.indexOf("hdiutil detach"))
        assertTrue(script.indexOf("hdiutil detach") < script.indexOf("open -n"))

        val swap = MacUpdater.swapScript()
        assertTrue(swap.contains("ditto \"\$1\" \"\$2.new\""))
        assertTrue("keeps the old bundle until the new one is in", swap.indexOf("mv \"\$2\" \"\$2.old\"") < swap.lastIndexOf("rm -rf \"\$2.old\""))
    }

    @Test
    fun `the deb update uses pkexec, or the software installer without it`() {
        val deb = File("/tmp/yarmiplaytv_1.9.0_amd64.deb")
        val launcher = File("/opt/yarmiplaytv/bin/YarmiplayTV")
        val withPkexec = DebUpdater.script(deb, launcher, listOf("x y"), "1.9.0", 7, relaunch = true, pkexec = true)
        assertTrue(withPkexec.contains("pkexec apt-get install -y '/tmp/yarmiplaytv_1.9.0_amd64.deb' && ok=1"))
        assertTrue(withPkexec.contains("export YARMIPLAYTV_UPDATE_FAILED='1.9.0'"))
        assertTrue(withPkexec.contains("nohup '/opt/yarmiplaytv/bin/YarmiplayTV' 'x y' >/dev/null 2>&1 &"))
        val without = DebUpdater.script(deb, launcher, emptyList(), "1.9.0", 7, relaunch = true, pkexec = false)
        assertTrue(without.contains("xdg-open '/tmp/yarmiplaytv_1.9.0_amd64.deb'"))
        assertFalse("not started again", without.contains("nohup"))
        assertFalse(without.contains("rm -f"))
    }

    @Test
    fun `the windows installer shows its progress`() {
        val script = WindowsUpdater(exe, emptyList()).script(File("C:/Temp/YarmiplayTV.msi"), listOf(1), relaunch = false)
        assertTrue(script.contains("/passive"))
        assertFalse(script.contains("/qn"))
    }

    @Test
    fun `the windows setup exe uses jpackage quiet not custom silent flags`() {
        val updater = WindowsUpdater(exe, listOf("keep"))
        val script = updater.exeScript(File("C:/Temp/YarmiplayTV-2.7.2-setup.exe"), listOf(1, 2), relaunch = true)
        assertTrue(script.contains("/quiet"))
        assertFalse("jpackage Exe rejects --silent", script.contains("--silent"))
        assertFalse("relaunch is done by the updater, not the installer", script.contains("--relaunch"))
        assertTrue(script.contains("Wait-Process -Id 1,2"))
        assertTrue(script.contains("YARMIPLAYTV_UPDATE_FAILED"))
    }

    @Test
    fun `a download of unknown length is not reported as zero`() = runBlocking {
        val body = "the package".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody(okio.Buffer().write(body), 4))
            val progress = mutableListOf<Float?>()
            val target = File(tmp.root, "chunked.zip")
            downloadVerified(Update("1.9.0", "", server.url("/YarmiplayTV.zip").toString(), sha), target, progress = { progress += it })
            assertEquals("the package", target.readText())
            assertEquals(listOf<Float?>(null), progress.distinct())
        }
    }

    @Test
    fun `the scripts parse in their shells`() {
        val windows = System.getProperty("os.name").lowercase().startsWith("windows")
        if (windows) {
            val launcher = File("C:/Apps/Yarmiplay's TV/YarmiplayTV.exe")
            val scripts = listOf(
                PortableUpdater.script(File("C:/Temp/it's.zip"), launcher, listOf("a b", "it's"), "1.9.0", listOf(1, 2), relaunch = true),
                WindowsUpdater(launcher, listOf("x")).script(File("C:/Temp/it's.msi"), listOf(1), relaunch = true),
            )
            scripts.forEach { script ->
                val file = tmp.newFile("script.txt").apply { writeText(script) }
                val check = "\$e = \$null; [void][System.Management.Automation.Language.Parser]::ParseInput((Get-Content -Raw -LiteralPath '${file.path}'), [ref]\$null, [ref]\$e); \$e.Count"
                val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", check).redirectErrorStream(true).start()
                val out = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                assertEquals("parse errors in:\n$script", "0", out)
                file.delete()
            }
        } else {
            val launcher = File("/Applications/Yarmiplay's TV.app/Contents/MacOS/YarmiplayTV")
            val scripts = listOf(
                MacUpdater.script(File("/tmp/it's.dmg"), launcher, File("/tmp/swap.sh"), listOf("a b"), "1.9.0", 1, relaunch = true),
                MacUpdater.swapScript(),
                DebUpdater.script(File("/tmp/it's.deb"), File("/opt/x/bin/Y"), listOf("a b"), "1.9.0", 1, relaunch = true, pkexec = true),
            )
            scripts.forEach { script ->
                val file = tmp.newFile("script.sh").apply { writeText(script) }
                val process = ProcessBuilder("/bin/sh", "-n", file.path).redirectErrorStream(true).start()
                val out = process.inputStream.bufferedReader().readText()
                assertEquals("$out\n$script", 0, process.waitFor())
                file.delete()
            }
        }
    }

    @Test
    fun `MacUpdater finds the bundle around the launcher`() {
        assertEquals("/Applications/YarmiplayTV.app", MacUpdater.bundleOf(macLauncher)?.invariantSeparatorsPath)
        assertNull(MacUpdater.bundleOf(File("/usr/bin/YarmiplayTV")))
    }

    @Test
    fun `downloads are checked against the page's checksum`() = runBlocking {
        val body = "the package".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody(Buffer().write(body))) }
            val url = server.url("/YarmiplayTV.zip").toString()
            val target = File(tmp.root, "update.zip")
            val progress = mutableListOf<Float?>()
            downloadVerified(Update("1.9.0", "", url, sha.uppercase()), target, progress = { progress += it })
            assertEquals("the package", target.readText())
            assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
            assertEquals(1f, progress.last())

            val damaged = File(tmp.root, "damaged.zip")
            val error = runCatching { downloadVerified(Update("1.9.0", "", url, "0".repeat(64)), damaged, progress = {}) }.exceptionOrNull()
            assertEquals("the download is damaged (checksum mismatch)", error?.message)
            assertFalse(damaged.exists())
            assertFalse(File(damaged.path + ".part").exists())

            val unlisted = runCatching { downloadVerified(Update("1.9.0", "", url, null), File(tmp.root, "x"), progress = {}) }.exceptionOrNull()
            assertEquals("the download page lists no checksum", unlisted?.message)
        }
    }
}
