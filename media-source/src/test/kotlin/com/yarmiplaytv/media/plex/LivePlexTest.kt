package com.yarmiplaytv.media.plex

import com.yarmiplaytv.media.jellyfin.ClientInfo
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the real sign-in path against plex.tv and the account's servers: with PLEX_LINK=1 it prints a
 * plex.tv/link code and waits for it, or uses PLEX_TOKEN (an account token). The token from a link is
 * kept in build/live-plex-token so later runs skip the code.
 */
class LivePlexTest {
    private val savedToken = File("build/live-plex-token")
    private val client = PlexClient(ClientInfo(deviceName = "YarmiplayTV live test", deviceId = "yarmiplaytv-live-test", version = "0.1"))

    @Test
    fun linkListConnectAndBrowse() = runBlocking {
        val token = System.getenv("PLEX_TOKEN").orEmpty()
            .ifEmpty { savedToken.takeIf { it.exists() }?.readText()?.trim().orEmpty() }
            .ifEmpty { if (System.getenv("PLEX_LINK") == "1") link() else "" }
        assumeTrue(token.isNotEmpty())

        val user = client.account(token)
        println("Account: ${user.username ?: user.title}")
        val servers = client.servers(token)
        check(servers.isNotEmpty()) { "No Plex Media Server on this account" }
        for (server in servers) {
            println("\nServer ${server.name} (owned=${server.owned}, ${server.connections.size} addresses)")
            for ((c, why) in client.probeAll(server)) {
                val kind = if (c.relay) "relay" else if (c.local) "local" else "remote"
                println("  [$kind] ${c.uri} -> ${why ?: "OK"}")
            }
            val session = runCatching { client.connect(server, token, user.username ?: "") }
                .onFailure { println("  connect failed: ${it.message}") }
                .getOrNull() ?: continue
            println("  connected via ${session.serverUrl}")
            val source = PlexSource(client, session)
            val libs = source.libraries()
            println("  libraries: ${libs.map { "${it.name} (${it.collectionType})" }}")
            libs.firstOrNull()?.let { println("  ${it.name}: ${source.children(it).size} items") }
            val recent = source.recent(5)
            println("  recent: ${recent.map { it.displayTitle }}")
            val playable = recent.firstOrNull { !it.isFolder }?.let { source.playable(it) } ?: continue
            val probe = Request.Builder().url(playable.url).header("Range", "bytes=0-1023").build()
            val code = client.http.newCall(probe).execute().use { it.code }
            println("  stream ${playable.fileName}: HTTP $code")
            check(code == 200 || code == 206) { "Stream URL returned HTTP $code" }
        }
    }

    private suspend fun link(): String {
        var token = ""
        client.link().collect { state ->
            when (state) {
                is PlexLinkState.WaitingForLink -> println(">>> Enter ${state.code} at https://plex.tv/link <<<")
                is PlexLinkState.Linked -> token = state.accountToken
            }
        }
        savedToken.parentFile.mkdirs()
        savedToken.writeText(token)
        return token
    }
}
