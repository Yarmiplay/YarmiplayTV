package com.yarmiplaytv.desktop

import com.yarmiplaytv.data.AppSettings
import com.yarmiplaytv.update.Update
import com.yarmiplaytv.update.UpdateInstaller
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchUpdateTest {
    private val update = Update("1.2.0", "https://tv.yarmiplay.com/", "https://tv.yarmiplay.com/YarmiplayTV.msi", "bb")
    private val automatic = AppSettings(checkForUpdates = true, installUpdatesOnLaunch = true)

    private class FakeUi(private val skipRightAway: Boolean = false) : LaunchUpdateUi {
        @Volatile var shown: Update? = null
        @Volatile var progress = -1f
        @Volatile var closed = false

        override fun show(update: Update, skip: () -> Unit) {
            shown = update
            if (skipRightAway) skip()
        }

        override fun progress(fraction: Float) {
            progress = fraction
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeInstaller(private val download: suspend ((Float) -> Unit) -> Unit = { it(1f) }) : UpdateInstaller {
        @Volatile var downloads = 0

        override suspend fun download(update: Update, progress: (Float) -> Unit) {
            downloads++
            download(progress)
        }

        override fun installAfterExit(relaunch: Boolean) = Unit
    }

    private fun decide(
        settings: AppSettings = automatic,
        installer: UpdateInstaller? = FakeInstaller(),
        failedInstall: String? = null,
        ui: LaunchUpdateUi = FakeUi(),
        found: Update? = update,
        checked: () -> Unit = {},
    ) = runBlocking {
        withTimeout(10_000) { updateBeforeLaunch(settings, installer, failedInstall, ui) { checked(); found } }
    }

    @Test
    fun opensRightAwayUnlessAutomaticUpdatesCanInstall() {
        var checks = 0
        assertEquals(LaunchUpdate.None, decide(settings = AppSettings(checkForUpdates = true, installUpdatesOnLaunch = false)) { checks++ })
        assertEquals(LaunchUpdate.None, decide(settings = AppSettings(checkForUpdates = false, installUpdatesOnLaunch = true)) { checks++ })
        assertEquals("portable, store or not Windows", LaunchUpdate.None, decide(installer = null) { checks++ })
        assertEquals(0, checks)
    }

    @Test
    fun aFailedInstallDoesNotTryAgainOnTheNextStart() {
        val installer = FakeInstaller()
        var checks = 0
        assertEquals(LaunchUpdate.None, decide(installer = installer, failedInstall = "1.2.0") { checks++ })
        assertEquals(0, checks)
        assertEquals(0, installer.downloads)
    }

    @Test
    fun opensWithoutAWindowWhenUpToDate() {
        val ui = FakeUi()
        assertEquals(LaunchUpdate.None, decide(ui = ui, found = null))
        assertEquals(null, ui.shown)
    }

    @Test
    fun downloadsWithProgressAndInstalls() {
        val ui = FakeUi()
        assertEquals(LaunchUpdate.Install(update), decide(ui = ui))
        assertEquals(update, ui.shown)
        assertEquals(1f, ui.progress)
        assertTrue(ui.closed)
    }

    @Test
    fun skipOpensTheAppWithoutWaitingForTheDownload() {
        val ui = FakeUi(skipRightAway = true)
        assertEquals(LaunchUpdate.Postponed(update), decide(installer = FakeInstaller { awaitCancellation() }, ui = ui))
        assertTrue(ui.closed)
    }

    @Test
    fun aFailedDownloadOpensTheAppWithTheError() {
        val ui = FakeUi()
        val result = decide(installer = FakeInstaller { error("checksum mismatch") }, ui = ui)
        assertTrue(result is LaunchUpdate.Postponed)
        assertEquals("checksum mismatch", (result as LaunchUpdate.Postponed).error?.message)
        assertTrue(ui.closed)
    }

    @Test
    fun relaunchKeepsTheArguments() {
        assertEquals("--room movie-night", windowsCommandLine(listOf("--room", "movie-night")))
        assertEquals(
            "--name \"Living room\" \"C:\\Videos\\My film.mkv\" \"C:\\My Clips\\\\\" \"say \\\"hi\\\"\" \"\"",
            windowsCommandLine(listOf("--name", "Living room", "C:\\Videos\\My film.mkv", "C:\\My Clips\\", "say \"hi\"", "")),
        )
        assertEquals("C:\\a\\b.mkv", windowsCommandLine(listOf("C:\\a\\b.mkv")))
        assertEquals("", windowsCommandLine(emptyList()))
    }
}
