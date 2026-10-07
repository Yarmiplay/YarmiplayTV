package com.yarmiplaytv.media.jellyfin

import com.yarmiplaytv.media.MediaSourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Identifies this app to Jellyfin (shown in the server's dashboard / Quick Connect prompt). */
data class ClientInfo(
    val clientName: String = "YarmiplayTV",
    val deviceName: String,
    val deviceId: String,
    val version: String,
)

/** A logged-in Jellyfin session; persist this to skip login next time. */
@Serializable
data class JellyfinSession(
    val serverUrl: String,
    val serverName: String,
    val serverId: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
    /** The Syncplay server whose host shares this Jellyfin with us; empty for servers the user signed in to. */
    val sharedBy: String = "",
    /** Every address the sharing host gave for it, tried again when [serverUrl] stops answering. */
    val candidateUrls: List<String> = emptyList(),
    /** The host stopped sharing it: its guest account answers 401. */
    val noLongerShared: Boolean = false,
) {
    val isShared: Boolean get() = sharedBy.isNotEmpty()
}

sealed interface QuickConnectState {
    data class WaitingForApproval(val code: String) : QuickConnectState
    data class Authorized(val session: JellyfinSession) : QuickConnectState
}

/**
 * Minimal Jellyfin REST client (10.8+). Uses only long-stable endpoints so it keeps working
 * across server versions without pulling in the full SDK.
 */
class JellyfinClient(
    val clientInfo: ClientInfo,
    http: OkHttpClient? = null,
) {
    internal val http: OkHttpClient = http ?: OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val jsonType = "application/json".toMediaType()

    fun authorizationHeader(token: String?): String = buildString {
        append("MediaBrowser Client=\"").append(escape(clientInfo.clientName)).append('"')
        append(", Device=\"").append(escape(clientInfo.deviceName)).append('"')
        append(", DeviceId=\"").append(escape(clientInfo.deviceId)).append('"')
        append(", Version=\"").append(escape(clientInfo.version)).append('"')
        if (token != null) append(", Token=\"").append(token).append('"')
    }

    private fun escape(v: String) = v.replace("\"", "'").replace("\n", " ")

    /** Accepts "host", "host:port", "http(s)://host[:port][/base]" and returns a canonical base URL. */
    fun normalizeServerUrl(input: String): String {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) throw MediaSourceException("Enter a server address")
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            s = "http://$s"
        }
        val url = s.toHttpUrlOrNull() ?: throw MediaSourceException("Invalid server address: $input")
        val withPort = if (!input.contains("://") && !Regex(":\\d+").containsMatchIn(input)) {
            url.newBuilder().port(8096).build()
        } else url
        return withPort.toString().trimEnd('/')
    }

    suspend fun publicInfo(serverUrl: String): PublicSystemInfo =
        get(serverUrl, null, "System/Info/Public") { }

    suspend fun quickConnectEnabled(serverUrl: String): Boolean =
        runCatching { getRaw(serverUrl, null, "QuickConnect/Enabled") {}.trim() == "true" }.getOrDefault(false)

    /**
     * Starts Quick Connect and polls until a user approves the code in another Jellyfin client
     * (Settings → Quick Connect). Cancel the collecting coroutine to abort.
     */
    fun quickConnect(serverUrl: String, pollIntervalMs: Long = 2500): Flow<QuickConnectState> = flow {
        val base = normalizeServerUrl(serverUrl)
        val info = publicInfo(base)
        val initiated: QuickConnectResult = try {
            post(base, null, "QuickConnect/Initiate", null) { }
        } catch (e: MediaSourceException) {
            if (e.httpCode == 401 || e.httpCode == 403) throw MediaSourceException("Quick Connect is disabled on this server", e.httpCode)
            if (e.httpCode == 405 || e.httpCode == 404) get(base, null, "QuickConnect/Initiate") { } else throw e
        }
        emit(QuickConnectState.WaitingForApproval(initiated.code))
        while (true) {
            delay(pollIntervalMs)
            val status: QuickConnectResult = get(base, null, "QuickConnect/Connect") { addQueryParameter("secret", initiated.secret) }
            if (status.authenticated) break
        }
        val auth: AuthenticationResult = post(base, null, "Users/AuthenticateWithQuickConnect",
            json.encodeToString(QuickConnectAuthBody.serializer(), QuickConnectAuthBody(initiated.secret))) { }
        emit(QuickConnectState.Authorized(sessionFrom(base, info, auth)))
    }

    suspend fun login(serverUrl: String, username: String, password: String): JellyfinSession {
        val base = normalizeServerUrl(serverUrl)
        val info = publicInfo(base)
        val auth: AuthenticationResult = try {
            post(base, null, "Users/AuthenticateByName",
                json.encodeToString(AuthenticateByNameBody.serializer(), AuthenticateByNameBody(username, password))) { }
        } catch (e: MediaSourceException) {
            if (e.httpCode == 401) throw MediaSourceException("Wrong username or password", 401) else throw e
        }
        return sessionFrom(base, info, auth)
    }

    /** Checks a stored session is still valid. */
    suspend fun validate(session: JellyfinSession): Boolean = try {
        getRaw(session.serverUrl, session.accessToken, "Users/Me") { }
        true
    } catch (e: MediaSourceException) {
        if (e.httpCode == 401 || e.httpCode == 403) false else throw e
    }

    suspend fun logout(session: JellyfinSession) {
        runCatching { postRaw(session.serverUrl, session.accessToken, "Sessions/Logout", null) { } }
    }

    private fun sessionFrom(base: String, info: PublicSystemInfo, auth: AuthenticationResult): JellyfinSession {
        val user = auth.user ?: throw MediaSourceException("Server returned no user")
        val token = auth.accessToken ?: throw MediaSourceException("Server returned no access token")
        return JellyfinSession(
            serverUrl = base,
            serverName = info.serverName ?: base,
            serverId = auth.serverId ?: info.id ?: "",
            userId = user.id,
            userName = user.name ?: "",
            accessToken = token,
        )
    }

    // --- HTTP plumbing ------------------------------------------------------------------

    internal fun url(base: String, path: String, query: HttpUrl.Builder.() -> Unit): HttpUrl {
        val root = base.toHttpUrlOrNull() ?: throw MediaSourceException("Invalid server address: $base")
        return root.newBuilder().addPathSegments(path).apply(query).build()
    }

    internal suspend inline fun <reified T> get(base: String, token: String?, path: String, noinline query: HttpUrl.Builder.() -> Unit): T =
        json.decodeFromString(getRaw(base, token, path, query))

    internal suspend inline fun <reified T> post(base: String, token: String?, path: String, body: String?, noinline query: HttpUrl.Builder.() -> Unit): T =
        json.decodeFromString(postRaw(base, token, path, body, query))

    internal suspend fun getRaw(base: String, token: String?, path: String, query: HttpUrl.Builder.() -> Unit): String =
        execute(Request.Builder().url(url(base, path, query)).get(), token)

    internal suspend fun postRaw(base: String, token: String?, path: String, body: String?, query: HttpUrl.Builder.() -> Unit): String =
        execute(Request.Builder().url(url(base, path, query)).post((body ?: "").toRequestBody(jsonType)), token)

    private suspend fun execute(builder: Request.Builder, token: String?): String = withContext(Dispatchers.IO) {
        val request = builder
            .header("Authorization", authorizationHeader(token))
            .header("Accept", "application/json")
            .build()
        try {
            http.newCall(request).execute().use { response ->
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
