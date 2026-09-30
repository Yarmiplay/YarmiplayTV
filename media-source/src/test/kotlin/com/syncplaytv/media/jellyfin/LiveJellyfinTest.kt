package com.syncplaytv.media.jellyfin

import com.syncplaytv.media.ResolveResult
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs against a real server when JELLYFIN_URL, JELLYFIN_TOKEN and JELLYFIN_USER_ID are set;
 * optionally JELLYFIN_RESOLVE=<file name> to check playlist resolution.
 */
class LiveJellyfinTest {
    private val url = System.getenv("JELLYFIN_URL").orEmpty()
    private val token = System.getenv("JELLYFIN_TOKEN").orEmpty()
    private val userId = System.getenv("JELLYFIN_USER_ID").orEmpty()

    @Test
    fun browseAndResolve() = runBlocking {
        assumeTrue(url.isNotEmpty() && token.isNotEmpty() && userId.isNotEmpty())
        val client = JellyfinClient(ClientInfo(deviceName = "SyncplayTV test", deviceId = "syncplaytv-test", version = "0.1"))
        val info = client.publicInfo(url)
        println("Server: ${info.serverName} ${info.version}")
        val source = JellyfinSource(client, JellyfinSession(url, info.serverName ?: "", info.id ?: "", userId, "", token))
        val libs = source.libraries()
        println("Libraries: ${libs.map { "${it.name} (${it.collectionType})" }}")
        source.refreshIndex(force = true)
        println("Indexed files: ${source.indexedFileCount}")
        val name = System.getenv("JELLYFIN_RESOLVE").orEmpty()
        if (name.isNotEmpty()) {
            when (val r = source.resolveByFilename(name)) {
                is ResolveResult.Found -> println("Resolved via ${r.matchedBy}: ${r.playable.fileName} size=${r.playable.sizeBytes} dur=${r.playable.durationSeconds}\n  ${r.playable.url.substringBefore("api_key")}")
                is ResolveResult.NotFound -> error("Not found: ${r.reason}")
            }
        }
    }
}
