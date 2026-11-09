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
        assertTrue(script.contains("\$env:YARMIPLAYTV_UPDATE_FAILED = \$sig"))
        assertTrue(script.contains("'1.9.0' + '|stage='"))
        assertTrue(script.contains("\$psi.UseShellExecute = \$false"))
        assertTrue(script.contains("\$psi.Arguments = '--room \"it''s mine\"'"))
        assertFalse(script.contains("Start-Process"))
        assertTrue("copy success is not merely starting a process", script.indexOf("Copy-Item") < script.indexOf("\$ok = \$true"))
        assertTrue(script.contains("update-failure.txt"))
        val noRelaunch = PortableUpdater.script(File("x.zip"), launcher, emptyList(), "1.9.0", listOf(1), relaunch = false)
        assertFalse(noRelaunch.contains("Start-Process"))
        assertTrue("a failure is still written when the app is not started again", noRelaunch.contains("update-failure.txt"))
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
        assertTrue(script.contains("/bin/sh '/tmp/swap.sh' \"\$src\" $app 2>&1"))
        assertTrue(script.contains("ok=1"))
        assertTrue("success is the swap, not mounting the image", script.indexOf("hdiutil attach") < script.indexOf("ok=1"))
        assertTrue("asks for an administrator when it can't write", script.contains("with administrator privileges"))
        assertTrue(script.contains("hdiutil detach \"\$mnt\""))
        assertTrue(script.contains("open -n $app --args '--file' 'a b.mkv'"))
        assertTrue(script.contains("YARMIPLAYTV_UPDATE_FAILED=\"\$ver|stage=\$stage|exit=\$code|\$detail_one\" nohup '/Applications/Yarmiplay'\\''s TV.app/Contents/MacOS/YarmiplayTV' '--file' 'a b.mkv' >/dev/null 2>&1 &"))
        assertTrue(script.contains("update-failure.txt"))
        assertTrue(script.contains("the disk image has no application"))
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
        assertTrue(withPkexec.contains("pkexec apt-get install -y '/tmp/yarmiplaytv_1.9.0_amd64.deb'"))
        assertTrue("apt has to exit 0 before the install counts", withPkexec.indexOf("pkexec apt-get") < withPkexec.indexOf("ok=1"))
        assertTrue(withPkexec.contains("export YARMIPLAYTV_UPDATE_FAILED=\"\$ver|stage=\$stage|exit=\$code|\$detail_one\""))
        assertTrue(withPkexec.contains("tail -c 400"))
        assertTrue(withPkexec.contains("update-failure.txt"))
        assertTrue(withPkexec.contains("nohup '/opt/yarmiplaytv/bin/YarmiplayTV' 'x y' >/dev/null 2>&1 &"))
        val without = DebUpdater.script(deb, launcher, emptyList(), "1.9.0", 7, relaunch = true, pkexec = false)
        assertTrue(without.contains("xdg-open '/tmp/yarmiplaytv_1.9.0_amd64.deb'"))
        assertTrue(without.indexOf("exit 0") < without.indexOf("nohup"))
        assertTrue(without.contains("xdg-open exited with code"))
        assertFalse("the package stays for the software installer", without.contains("rm -f '/tmp/yarmiplaytv_1.9.0_amd64.deb'"))
    }

    @Test
    fun `the windows installer shows its progress`() {
        val script = WindowsUpdater(exe, emptyList()).script(File("C:/Temp/YarmiplayTV.msi"), listOf(1), relaunch = false)
        assertTrue(script.contains("/passive"))
        assertFalse(script.contains("/qn"))
    }

    @Test
    fun `the windows setup exe installs silently into the current folder`() {
        val launcher = File("C:/Users/Sam/AppData/Local/Yarmiplay TV/YarmiplayTV.exe")
        val updater = WindowsUpdater(launcher, listOf("keep"))
        val script = updater.exeScript(File("C:/Temp/YarmiplayTV-2.7.4-setup.exe"), listOf(1, 2), relaunch = true)
        val dir = launcher.parentFile.path
        val setupArgs = windowsCommandLine(listOf("--silent", "--dir", dir))
        assertTrue(script.contains("\$psi.Arguments = ${psQuoted(setupArgs)}"))
        assertTrue("the path with a space stays inside one command line", setupArgs.contains("\"$dir\""))
        assertTrue(script.indexOf("Process]::Start") < script.indexOf("\$ok = \$code -in 0, 3010"))
        assertFalse("the updater starts the new copy itself", script.contains("--relaunch"))
        assertFalse(script.contains("/quiet"))
        assertTrue(script.contains("Wait-Process -Id 1,2"))
        assertTrue(script.contains("YARMIPLAYTV_UPDATE_FAILED"))
        assertTrue(script.contains("Unblock-File"))
        assertTrue(script.contains("Get-Process -Name 'YarmiplayTV'"))
        assertTrue(script.contains("keep"))
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
            assertTrue(error?.message.orEmpty().contains("Couldn't verify the download"))
            assertTrue(error?.message.orEmpty().contains("checksum mismatch"))
            assertFalse(damaged.exists())
            assertFalse(File(damaged.path + ".part").exists())

            val unlisted = runCatching { downloadVerified(Update("1.9.0", "", url, null), File(tmp.root, "x"), progress = {}) }.exceptionOrNull()
            assertTrue(unlisted?.message.orEmpty().contains("Couldn't verify the download"))
            assertTrue(unlisted?.message.orEmpty().contains("no checksum"))

            server.enqueue(MockResponse().setResponseCode(404))
            val missing = runCatching { downloadVerified(Update("1.9.0", "", url, sha), File(tmp.root, "missing.msi"), progress = {}) }.exceptionOrNull()
            assertTrue(missing?.message.orEmpty().contains("Couldn't download the update"))
            assertTrue(missing?.message.orEmpty().contains("HTTP 404"))
            assertFalse(missing?.message.orEmpty().contains("token"))
        }
    }

    @Test
    fun `a windows install folder with spaces stays one argument`() {
        val launcher = File("C:/Users/Dor Farhi/AppData/Local/YarmiplayTV/YarmiplayTV.exe")
        val script = WindowsUpdater(launcher, listOf("--room", "movie night")).exeScript(
            File("C:/Users/Dor Farhi/AppData/Local/Temp/YarmiplayTV-2.9.8-setup.exe"),
            listOf(4),
            relaunch = true,
        )
        val dir = parentDirOf(launcher)!!.path
        val command = windowsCommandLine(listOf("--silent", "--dir", dir))
        assertEquals(listOf("--silent", "--dir", dir), splitWindowsCommandLine(command))
        assertTrue(script.contains(psQuoted(command)))
        assertTrue(script.indexOf("\$psi.UseShellExecute = \$false") < script.indexOf("if (\$needAdmin)"))
        assertTrue(script.contains("\$psi.UseShellExecute = \$true"))
        assertTrue(script.contains("runas"))
        val encoded = script.substringAfter("-EncodedCommand ").substringBefore("'")
        val elevated = String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_16LE)
        assertTrue(elevated.contains("\$psi.UseShellExecute = \$false"))
        assertTrue(elevated.contains(psQuoted(command)))
        assertFalse(elevated.contains("ArgumentList"))
        assertFalse(script.contains("-ArgumentList '--silent'"))
        assertTrue(script.contains("update-failure.txt"))
        assertTrue(script.contains("GetFolderPath('LocalApplicationData')"))
        assertTrue(script.indexOf("[System.Diagnostics.Process]::Start") < script.indexOf("\$ok = \$code -in 0, 3010"))
        val msiPath = "C:\\Users\\Dor Farhi\\AppData\\Local\\Temp\\YarmiplayTV-2.9.9.msi"
        val msi = WindowsUpdater(launcher, emptyList()).msiScript(File(msiPath), listOf(4), relaunch = false)
        assertTrue(msi.contains(psQuoted(msiPath)) || msi.contains(psQuoted(File(msiPath).path)))
        assertTrue(msi.contains("\$psi.UseShellExecute = \$false"))
        assertTrue(msi.contains("/le"))
        assertFalse(msi.contains("Start-Process"))
        assertFalse("msiexec is not elevated through a shell", msi.contains("runas"))
        assertTrue(msi.contains("update-failure.txt"))
        val backslashDir = "C:\\Users\\Dor Farhi\\AppData\\Local\\YarmiplayTV"
        val backslashExe = File("$backslashDir\\YarmiplayTV.exe")
        assertEquals(backslashDir, parentDirOf(backslashExe)!!.path)
        val backslashCommand = windowsCommandLine(listOf("--silent", "--dir", backslashDir))
        assertEquals(listOf("--silent", "--dir", backslashDir), splitWindowsCommandLine(backslashCommand))
        val fromBackslash = WindowsUpdater(backslashExe, emptyList()).exeScript(File("C:\\Temp\\setup.exe"), listOf(4), relaunch = false)
        assertTrue(fromBackslash.contains(psQuoted(backslashCommand)))
        assertEquals(
            "C:/Users/Dor Farhi/AppData/Local/YarmiplayTV",
            resolveWindowsInstallDir(backslashExe, home = "C:\\Users\\Dor Farhi") { null }.path.replace('\\', '/'),
        )
    }

    @Test
    fun `mac and portable paths with spaces stay quoted`() {
        val mac = File("/Users/Dor Farhi/Applications/YarmiplayTV.app/Contents/MacOS/YarmiplayTV")
        val script = MacUpdater.script(
            File("/tmp/YarmiplayTV-2.9.8.dmg"), mac, File("/tmp/swap.sh"), emptyList(), "2.9.8", pid = 9, relaunch = true,
        )
        assertTrue(script.contains("'/Users/Dor Farhi/Applications/YarmiplayTV.app'"))
        assertTrue(script.contains("'/Users/Dor Farhi/Applications/YarmiplayTV.app/Contents/MacOS/YarmiplayTV'"))
        assertTrue(script.contains("\"\$HOME/Library/Application Support/YarmiplayTV/update-failure.txt\""))
        val portable = PortableUpdater.script(
            File("C:/Temp/YarmiplayTV-2.9.8-portable.zip"),
            File("C:/Users/Dor Farhi/YarmiplayTV/YarmiplayTV.exe"),
            emptyList(),
            "2.9.8",
            listOf(3),
            relaunch = true,
        )
        val dir = parentDirOf(File("C:/Users/Dor Farhi/YarmiplayTV/YarmiplayTV.exe"))!!.path
        assertTrue(portable.contains(psQuoted(dir)))
        assertTrue("the spaced folder is one quoted path", psQuoted(dir).contains("Dor Farhi"))
        assertFalse(portable.contains("'C:/Users/Dor'"))
        assertTrue(portable.contains("LiteralPath"))
        assertTrue(portable.contains("the archive has no YarmiplayTV.exe"))
    }

    @Test
    fun `path resolution handles per-user, per-machine, non-ASCII, OneDrive, colons, and unsafe user profile`() {
        val localAppData = "C:\\Users\\דור פרחי\\AppData\\Local"
        val dorEnv: (String) -> String? = { key ->
            when (key) {
                "LOCALAPPDATA" -> "C:\\Users\\Dor\\AppData\\Local"
                "USERPROFILE" -> "C:\\Users\\Dor"
                "ProgramFiles" -> "C:\\Program Files"
                "ProgramFiles(x86)" -> "C:\\Program Files (x86)"
                else -> null
            }
        }

        // User profile root is never accepted as an install dir; falls back to %LOCALAPPDATA%\YarmiplayTV
        val fromProfile = resolveWindowsInstallDir(
            launcher = File("C:\\Users\\Dor\\YarmiplayTV.exe"),
            appImageDir = File("C:\\Users\\Dor"),
            registryDir = null,
            home = "C:\\Users\\Dor",
            env = dorEnv,
        )
        assertEquals("C:/Users/Dor/AppData/Local/YarmiplayTV", fromProfile.path.replace('\\', '/'))

        // Corrupted path with multiple colons is rejected; falls back to registry or LocalAppData
        val fromCorrupted = resolveWindowsInstallDir(
            launcher = File("C:\\Users\\Dor: C:\\Users\\Dor\\app\\YarmiplayTV.exe"),
            appImageDir = File("C:\\Users\\Dor: C:\\Users\\Dor\\app"),
            registryDir = File("C:\\Program Files\\YarmiplayTV"),
            home = "C:\\Users\\Dor",
            env = dorEnv,
        )
        assertEquals("C:/Program Files/YarmiplayTV", fromCorrupted.path.replace('\\', '/'))

        // Per-machine install in Program Files is preserved and recognized as per-machine
        val perMachine = resolveWindowsInstallDir(
            launcher = File("C:\\Program Files\\YarmiplayTV\\YarmiplayTV.exe"),
            appImageDir = File("C:\\Program Files\\YarmiplayTV"),
            registryDir = null,
            home = "C:\\Users\\Dor",
            env = dorEnv,
        )
        assertEquals("C:/Program Files/YarmiplayTV", perMachine.path.replace('\\', '/'))
        assertTrue(isPerMachineInstallDir(perMachine, env = dorEnv))
        assertFalse(isSafePortableDir(perMachine, home = "C:\\Users\\Dor", env = dorEnv))

        // Non-ASCII and OneDrive-redirected subfolder is accepted, while OneDrive root/Documents root is rejected
        val hebrewHome = "C:\\Users\\דור פרחי"
        val hebrewEnv: (String) -> String? = { key ->
            when (key) {
                "USERPROFILE" -> hebrewHome
                "OneDrive" -> "$hebrewHome\\OneDrive"
                else -> null
            }
        }
        val oneDriveApp = File("$hebrewHome\\OneDrive\\Documenten\\YarmiplayTV")
        assertTrue(isSafeWindowsInstallDir(oneDriveApp, home = hebrewHome, env = hebrewEnv))
        assertTrue(isSafePortableDir(oneDriveApp, home = hebrewHome, env = hebrewEnv))
        assertFalse(isSafeWindowsInstallDir(File("$hebrewHome\\OneDrive"), home = hebrewHome, env = hebrewEnv))
        assertFalse(isSafeWindowsInstallDir(File("$hebrewHome\\OneDrive\\Documenten"), home = hebrewHome, env = hebrewEnv))

        // Running app image resolves from java.home (<installDir>/runtime) when jpackage.app-path is missing or in user profile
        val fakeInstall = tmp.newFolder("YarmiplayTV-install")
        val fakeExe = File(fakeInstall, "YarmiplayTV.exe").apply { writeText("exe") }
        val fakeRuntime = File(fakeInstall, "runtime").apply { mkdirs() }
        val resolvedLauncher = resolveInstalledLauncher(
            osName = "Windows 11",
            appPath = "C:\\Users\\Dor\\YarmiplayTV.exe",
            javaHome = fakeRuntime.path,
            classPath = null,
            processCommand = null,
            home = "C:\\Users\\Dor",
            env = dorEnv,
        )
        assertEquals(fakeExe.absolutePath.replace('\\', '/'), resolvedLauncher?.path?.replace('\\', '/'))

        // When a portable marker exists in an unsafe or per-machine folder, it falls back to the proper setup.exe installer
        val unsafePortableLauncher = File("C:\\Program Files\\YarmiplayTV\\YarmiplayTV.exe")
        assertEquals(InstallKind.MSI, kind("Windows 11", env = dorEnv, launcher = unsafePortableLauncher, portable = true))
        val unsafeSource = updateSource(InstallKind.PORTABLE, unsafePortableLauncher) as UpdateSource.DownloadPage
        assertEquals("windows", unsafeSource.platform)
        assertTrue(unsafeSource.installer is WindowsUpdater)

        // Command-line building with non-ASCII, spaces, quotes, and backslashes
        val nonAsciiDir = "$localAppData\\YarmiplayTV"
        val cmd = windowsCommandLine(listOf("--silent", "--dir", nonAsciiDir))
        assertEquals("--silent --dir \"$nonAsciiDir\"", cmd)
    }
}
