package com.yarmiplaytv.media.plex

import com.yarmiplaytv.media.MediaSourceException
import com.yarmiplaytv.media.jellyfin.ClientInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A signed-in Plex server; persist this to skip linking next time. */
@Serializable
data class PlexSession(
    val serverUrl: String,
    val serverName: String,
    val machineId: String,
    val userName: String,
    /** Token for this server (differs from the account token on servers shared with the user). */
    val serverToken: String,
    /** plex.tv account token, used to find the server again when its address changes. */
    val accountToken: String,
)

sealed interface PlexLinkState {
    /** Show [code] and ask the user to enter it at plex.tv/link. */
    data class WaitingForLink(val code: String) : PlexLinkState
    data class Linked(val accountToken: String, val userName: String) : PlexLinkState
}

/** A server on the user's Plex account, with every address plex.tv knows for it. */
data class PlexServer(
    val name: String,
    val machineId: String,
    val accessToken: String,
    val owned: Boolean,
    val connections: List<PlexResourceConnection>,
)

/**
 * Minimal Plex client: PIN linking and server discovery through plex.tv, then the server's own
 * REST API (JSON). [plexTvUrl] is only overridden by tests.
 */
class PlexClient(
    val clientInfo: ClientInfo,
    http: OkHttpClient? = null,
    private val plexTvUrl: String = "https://plex.tv",
) {
    internal val http: OkHttpClient = http ?: OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val probeHttp: OkHttpClient = this.http.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    /**
     * Creates a PIN and polls until the user enters its code at plex.tv/link. Cancel the collecting
     * coroutine to abort; throws when the code expires.
     */
    fun link(pollIntervalMs: Long = 2000): Flow<PlexLinkState> = flow {
        val pin: PlexPin = json.decodeFromString(execute(http, plexTvUrl, null, "api/v2/pins", post = true) {
            addQueryParameter("strong", "false")
        })
        emit(PlexLinkState.WaitingForLink(pin.code))
        val expiresAt = System.currentTimeMillis() + (pin.expiresIn ?: 900) * 1000L
        var token: String? = null
        while (token == null) {
            if (System.currentTimeMillis() > expiresAt) throw MediaSourceException("The code expired; try again")
            delay(pollIntervalMs)
            val status: PlexPin = json.decodeFromString(execute(http, plexTvUrl, null, "api/v2/pins/${pin.id}") { })
            token = status.authToken?.takeIf { it.isNotBlank() }
        }
        val user = runCatching { account(token) }.getOrNull()
        emit(PlexLinkState.Linked(token, user?.username ?: user?.title ?: ""))
    }

    suspend fun account(accountToken: String): PlexUser =
        json.decodeFromString(execute(http, plexTvUrl, accountToken, "api/v2/user") { })

    /** Media servers on the account (owned and shared). */
    suspend fun servers(accountToken: String): List<PlexServer> {
        val resources: List<PlexResource> = json.decodeFromString(execute(http, plexTvUrl, accountToken, "api/v2/resources") {
            addQueryParameter("includeHttps", "1")
            addQueryParameter("includeRelay", "1")
        })
        return resources
            .filter { r -> r.provides?.split(',')?.any { it.trim() == "server" } == true }
            .map { r ->
                PlexServer(
                    name = r.name,
                    machineId = r.clientIdentifier,
                    accessToken = r.accessToken ?: accountToken,
                    owned = r.owned ?: false,
                    connections = r.connections,
                )
            }
    }

    /** Probes the server's addresses (all at once) and returns a session on the best reachable one: local, remote, then relay. */
    suspend fun connect(server: PlexServer, accountToken: String, userName: String): PlexSession {
        val ordered = server.connections.sortedBy { c -> if (c.relay) 2 else if (c.local) 0 else 1 }
        val reachable = coroutineScope {
            ordered.map { c -> async { c to probe(c.uri, server.accessToken, server.machineId) } }.awaitAll()
        }
        val best = reachable.firstOrNull { it.second }?.first
            ?: throw MediaSourceException("Can't reach ${server.name}; is it online?")
        return PlexSession(
            serverUrl = best.uri.trimEnd('/'),
            serverName = server.name,
            machineId = server.machineId,
            userName = userName,
            serverToken = server.accessToken,
            accountToken = accountToken,
        )
    }

    private suspend fun probe(uri: String, token: String, machineId: String): Boolean = try {
        val identity: PlexResponse = json.decodeFromString(execute(probeHttp, uri, token, "identity") { })
        identity.container.machineIdentifier.let { it == null || it == machineId }
    } catch (e: MediaSourceException) {
        false
    }

    /**
     * Checks a stored session. Returns it unchanged when the server answers, a session on a new
     * address when the old one stopped working, null when the token was revoked. Throws when the
     * server can't be reached at all (keep the session; it may be offline).
     */
    suspend fun refresh(session: PlexSession): PlexSession? {
        try {
            execute(probeHttp, session.serverUrl, session.serverToken, "identity") { }
            return session
        } catch (e: MediaSourceException) {
            if (e.httpCode == 401) return null
            if (e.httpCode != null) throw e
        }
        val server = try {
            servers(session.accountToken).firstOrNull { it.machineId == session.machineId }
        } catch (e: MediaSourceException) {
            if (e.httpCode == 401) return null
            throw e
        } ?: return null
        return connect(server, session.accountToken, session.userName)
    }

    // --- HTTP plumbing ------------------------------------------------------------------

    internal fun url(base: String, path: String, query: HttpUrl.Builder.() -> Unit): HttpUrl {
        val root = base.toHttpUrlOrNull() ?: throw MediaSourceException("Invalid server address: $base")
        return root.newBuilder().addEncodedPathSegments(path.trimStart('/')).apply(query).build()
    }

    internal suspend inline fun <reified T> get(base: String, token: String, path: String, noinline query: HttpUrl.Builder.() -> Unit): T =
        json.decodeFromString(getRaw(base, token, path, emptyMap(), query))

    internal suspend fun getRaw(
        base: String,
        token: String?,
        path: String,
        headers: Map<String, String> = emptyMap(),
        query: HttpUrl.Builder.() -> Unit,
    ): String = execute(http, base, token, path, headers = headers, query = query)

    private suspend fun execute(
        client: OkHttpClient,
        base: String,
        token: String?,
        path: String,
        post: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        query: HttpUrl.Builder.() -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(base, path, query)).apply {
            if (post) post(ByteArray(0).toRequestBody()) else get()
            header("Accept", "application/json")
            header("X-Plex-Product", clientInfo.clientName)
            header("X-Plex-Version", clientInfo.version)
            header("X-Plex-Client-Identifier", clientInfo.deviceId)
            header("X-Plex-Device-Name", clientInfo.deviceName)
            if (token != null) header("X-Plex-Token", token)
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val msg = when (response.code) {
                        401 -> "Not authorised (401)"
                        403 -> "Forbidden (403)"
                        404 -> "Not found (404): ${request.url.encodedPath}"
                        else -> "HTTP ${response.code}: ${text.take(200)}"
                    }
                    throw MediaSourceException(msg, response.code)
                }
                text
            }
        } catch (e: IOException) {
            throw MediaSourceException("Cannot reach ${request.url.host}:${request.url.port} (${e.message})", cause = e)
        }
    }
}
