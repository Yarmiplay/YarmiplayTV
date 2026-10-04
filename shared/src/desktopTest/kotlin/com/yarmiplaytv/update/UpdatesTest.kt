package com.yarmiplaytv.update

import com.yarmiplaytv.data.SettingsStore
import com.yarmiplaytv.data.desktopSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdatesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var scope: CoroutineScope
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        server.start()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        store = desktopSettingsStore(tmp.root, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun manifest(windows: String = "1.2.0") = """
        {"page": "./", "platforms": {
          "android": {"version": "1.1", "file": "YarmiplayTV.apk", "sha256": "aa"},
          "windows": {"version": "$windows", "file": "YarmiplayTV.msi", "sha256": "bb"}
        }}
    """.trimIndent()

    private fun manifestUrl() = server.url("/YarmiplayTV/version.json").toString()

    private fun updates(installer: UpdateInstaller? = null) =
        Updates(scope, store, "1.1.0", UpdateChecker(manifestUrl())).apply {
            platform = "windows"
            this.installer = installer
        }

    private fun awaitTrue(condition: () -> Boolean) = runBlocking { withTimeout(10_000) { while (!condition()) delay(20) } }

    @Test
    fun `compares versions numerically with missing parts as zero`() {
        assertTrue(AppVersions.isNewer("1.2", "1.1.0"))
        assertTrue(AppVersions.isNewer("1.10", "1.9"))
        assertTrue(AppVersions.isNewer("v2.0", "1.9.9"))
        assertFalse(AppVersions.isNewer("1.1", "1.1.0"))
        assertFalse(AppVersions.isNewer("1.0.9", "1.1"))
        assertFalse(AppVersions.isNewer("1.2", "dev"))
        assertFalse(AppVersions.isNewer("latest", "1.1"))
    }

    @Test
    fun `reads this platform's package with paths relative to the manifest`() {
        val update = UpdateChecker.parse(manifest(), "windows", "1.1.0", "https://tv.yarmiplay.com/version.json")
        assertEquals(Update("1.2.0", "https://tv.yarmiplay.com/", "https://tv.yarmiplay.com/YarmiplayTV.msi", "bb"), update)
        assertNull(UpdateChecker.parse(manifest(), "android", "1.1", manifestUrl()))
        assertNull(UpdateChecker.parse(manifest(), "linux", "1.1.0", manifestUrl()))
    }

    @Test
    fun `check returns null when the page is unreachable or broken`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("<html>not json</html>"))
        val checker = UpdateChecker(manifestUrl())
        runBlocking {
            assertNull(checker.check("windows", "1.1.0"))
            assertNull(checker.check("windows", "1.1.0"))
        }
    }

    @Test
    fun `shows a newer version and remembers when it's dismissed`() {
        server.enqueue(MockResponse().setBody(manifest()))
        val first = updates()
        first.checkOnLaunch()
        awaitTrue { first.state.value != null }
        assertEquals(UpdateState.Available(UpdateChecker.parse(manifest(), "windows", "1.1.0", manifestUrl())!!), first.state.value)
        assertTrue(server.takeRequest().requestUrl.toString().endsWith("/YarmiplayTV/version.json"))

        first.dismiss()
        assertNull(first.state.value)
        awaitTrue { runBlocking { store.current().dismissedUpdate } == "1.2.0" }

        server.enqueue(MockResponse().setBody(manifest()))
        val second = updates()
        second.checkOnLaunch()
        awaitTrue { server.requestCount == 2 }
        runBlocking { delay(300) }
        assertNull(second.state.value)
    }

    @Test
    fun `doesn't contact the page when checking is off`() {
        runBlocking { store.saveUpdatePrefs(check = false, installOnLaunch = false) }
        val updates = updates()
        updates.checkOnLaunch()
        runBlocking { delay(500) }
        assertEquals(0, server.requestCount)
        assertNull(updates.state.value)
    }

    @Test
    fun `installs on launch by downloading and installing on exit`() {
        runBlocking { store.saveUpdatePrefs(check = true, installOnLaunch = true) }
        server.enqueue(MockResponse().setBody(manifest()))
        val installer = FakeInstaller()
        val updates = updates(installer)
        updates.checkOnLaunch()
        awaitTrue { updates.state.value is UpdateState.Ready }
        assertEquals("1.2.0", installer.downloaded?.version)
        assertNull(installer.relaunch)

        updates.onExit()
        assertEquals(false, installer.relaunch)
    }

    @Test
    fun `a failed download keeps the notice with the error`() {
        server.enqueue(MockResponse().setBody(manifest()))
        val updates = updates(FakeInstaller(failWith = "checksum mismatch"))
        updates.checkOnLaunch()
        awaitTrue { updates.state.value != null }
        updates.download(thenRestart = true)
        awaitTrue { updates.state.value is UpdateState.Failed }
        assertEquals("checksum mismatch", (updates.state.value as UpdateState.Failed).message)
    }

    @Test
    fun `restart installs right away and closes the app`() {
        server.enqueue(MockResponse().setBody(manifest()))
        val installer = FakeInstaller()
        var exited = false
        val updates = updates(installer).apply { exitApp = { exited = true } }
        updates.checkOnLaunch()
        awaitTrue { updates.state.value != null }
        updates.download(thenRestart = true)
        awaitTrue { exited }
        assertEquals(true, installer.relaunch)
        installer.relaunch = null
        updates.onExit()
        assertNull("installs once", installer.relaunch)
    }

    private class FakeInstaller(private val failWith: String? = null) : UpdateInstaller {
        @Volatile var downloaded: Update? = null
        @Volatile var relaunch: Boolean? = null

        override suspend fun download(update: Update, progress: (Float) -> Unit) {
            progress(0.5f)
            failWith?.let { error(it) }
            downloaded = update
        }

        override fun installAfterExit(relaunch: Boolean) {
            this.relaunch = relaunch
        }
    }
}
