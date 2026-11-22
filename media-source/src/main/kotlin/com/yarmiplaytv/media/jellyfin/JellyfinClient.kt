package com.yarmiplaytv.media.jellyfin

import com.yarmiplaytv.media.LanHosts
import com.yarmiplaytv.media.MediaSourceException
import com.yarmiplaytv.media.ServerMismatchException
import com.yarmiplaytv.media.SharedHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

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
    /**
     * The local address [serverUrl]'s name answers at on its home network, where the name may lead to the router
     * instead (an HTTPS server found by its IP address, whose certificate is for the name); empty when it has none.
     */
    val lanAddress: String = "",
    /**
     * Try `https://` on this host/port before [serverUrl] when [serverUrl] is plain `http://` (ambiguous after
     * 2.9.1 rewrote some saved HTTPS entries to HTTP). Never written for a URL that is already `https://`.
     */
    val preferHttps: Boolean = false,
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
    /** Looks up server names; the app maps a Syncplay server's name to the address it reached it at. */
    dns: Dns = Dns.SYSTEM,
    /**
     * Asked before a server the user gave as `https://` ([server] is that URL) is used over plain `http://` after
     * its HTTPS handshake looked like plain HTTP; [signIn] when a password or Quick Connect would go over it. False
     * leaves the server unreachable. Servers given as `http://` that are only tried over HTTPS first
     * ([JellyfinSession.preferHttps]) fall back without asking. The default allows it.
     */
    private val confirmPlainHttp: suspend (server: String, signIn: Boolean) -> Boolean = { _, _ -> true },
) {
    internal val http: OkHttpClient = http ?: SharedHttp.base.newBuilder()
        .dns(dns)
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

    /**
     * [normalizeServerUrl], and for HTTPS on an IP address the name the server's certificate is for, which the
     * address is kept for ([LanHosts]): the certificate couldn't be verified at the address.
     */
    suspend fun resolveServerUrl(input: String): String = LanHosts.byCertificateName(normalizeServerUrl(input))

    /**
     * A URL that answers right now: HTTPS first when [input] (or [preferHttps]) calls for it, then plain HTTP on
     * the same host/port only after a plain-HTTP TLS handshake error — for an `https://` [input] only once
     * [confirmPlainHttp] agreed (it's a sign-in). Does not decide what to persist — callers that sign in should keep
     * the user's preferred scheme via [preferredServerUrl].
     */
    suspend fun resolveReachableUrl(input: String, preferHttps: Boolean = false): String {
        val normalized = resolveServerUrl(input)
        val typedHttps = normalized.startsWith("https://", ignoreCase = true)
        val httpsFirst = when {
            typedHttps -> normalized
            preferHttps -> httpsUrl(normalized) ?: return normalized
            else -> return normalized
        }
        return try {
            publicInfo(httpsFirst)
            httpsFirst
        } catch (httpsErr: MediaSourceException) {
            if (!isPlainHttpSslFailure(httpsErr)) throw httpsErr
            val http = plainHttpUrl(httpsFirst) ?: throw httpsErr
            try {
                // Nothing secret goes in this request: whether HTTP answers at all decides if there's anything to ask.
                publicInfo(http)
            } catch (httpErr: MediaSourceException) {
                throw bothSchemesFailed(httpsFirst, httpsErr, http, httpErr)
            }
            if (typedHttps && !confirmPlainHttp(httpsFirst, true)) throw notOverHttps(httpsFirst, httpsErr)
            http
        }
    }

    /**
     * The URL to keep on a signed-in session: the user's preferred scheme from [input], never the ephemeral
     * HTTP fallback [resolveReachableUrl] may have used.
     */
    suspend fun preferredServerUrl(input: String): String = resolveServerUrl(input)

    /**
     * When [error] means the probe URL speaks plain HTTP, the same host over `http://` if it still validates.
     * Returns that working base only — does not change what should be saved ([JellyfinSession.serverUrl]). A
     * session saved as `https://` goes over HTTP only once [confirmPlainHttp] agreed, before its token is sent.
     */
    suspend fun reachablePlainHttp(session: JellyfinSession, error: Throwable): String? {
        if (!isPlainHttpSslFailure(error)) return null
        val probe = preferredProbeUrl(session)
        val http = plainHttpUrl(probe) ?: plainHttpUrl(session.serverUrl) ?: run {
            session.serverUrl.takeIf { it.startsWith("http://", ignoreCase = true) }
        } ?: return null
        if (session.serverUrl.trim().startsWith("https://", ignoreCase = true)) {
            if (runCatching { publicInfo(http) }.isFailure) return null
            if (!confirmPlainHttp(probe, false)) return null
        }
        return http.takeIf { runCatching { validateAt(it, session.accessToken, session.serverId) }.getOrNull() == true }
    }

    /** @deprecated Use [reachablePlainHttp]; kept name for older call sites during 2.9.2. */
    suspend fun sessionOnPlainHttp(session: JellyfinSession, error: Throwable): JellyfinSession? {
        val http = reachablePlainHttp(session, error) ?: return null
        return session.copy(serverUrl = http, lanAddress = LanHosts.hintFor(http).orEmpty())
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
        val preferred = preferredServerUrl(serverUrl)
        val base = resolveReachableUrl(preferred)
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
        emit(QuickConnectState.Authorized(sessionFrom(preferred, info, auth)))
    }

    suspend fun login(serverUrl: String, username: String, password: String): JellyfinSession {
        val preferred = preferredServerUrl(serverUrl)
        val base = resolveReachableUrl(preferred)
        val info = publicInfo(base)
        val auth: AuthenticationResult = try {
            post(base, null, "Users/AuthenticateByName",
                json.encodeToString(AuthenticateByNameBody.serializer(), AuthenticateByNameBody(username, password))) { }
        } catch (e: MediaSourceException) {
            if (e.httpCode == 401) throw MediaSourceException("Wrong username or password", 401) else throw e
        }
        return sessionFrom(preferred, info, auth)
    }

    companion object {
        /**
         * True when the failure means we spoke TLS to a port that only speaks plain HTTP
         * (OpenSSL "Unsupported or unrecognized SSL message", WRONG_VERSION_NUMBER, …).
         */
        fun isPlainHttpSslFailure(error: Throwable): Boolean {
            var e: Throwable? = error
            while (e != null) {
                val msg = e.message.orEmpty().lowercase()
                if ("unsupported or unrecognized ssl message" in msg ||
                    "wrong_version_number" in msg ||
                    "plaintext http request was sent to https port" in msg ||
                    "received fatal alert: protocol_version" in msg && e is SSLException
                ) {
                    return true
                }
                // JVM/OkHttp wrapping: SSLException whose message names a cleartext peer.
                if (e is SSLException && ("unrecognized ssl" in msg || "plaintext" in msg || "wrong version" in msg)) {
                    return true
                }
                e = e.cause
            }
            return false
        }

        /** Same host and port as [httpsUrl] but `http://`, or null when [httpsUrl] is not HTTPS. */
        fun plainHttpUrl(httpsUrl: String): String? {
            val url = httpsUrl.toHttpUrlOrNull() ?: return null
            if (!url.isHttps) return null
            return url.newBuilder().scheme("http").build().toString().trimEnd('/')
        }

        /** Same host and port as [httpUrl] but `https://`, or null when [httpUrl] is not HTTP. */
        fun httpsUrl(httpUrl: String): String? {
            val url = httpUrl.toHttpUrlOrNull() ?: return null
            if (url.isHttps) return null
            if (!url.scheme.equals("http", ignoreCase = true)) return null
            return url.newBuilder().scheme("https").build().toString().trimEnd('/')
        }

        /** First URL to probe for [session]: HTTPS when the saved URL is HTTPS or [JellyfinSession.preferHttps]. */
        fun preferredProbeUrl(session: JellyfinSession): String {
            val url = session.serverUrl.trim().trimEnd('/')
            if (url.startsWith("https://", ignoreCase = true)) return url
            if (session.preferHttps) return httpsUrl(url) ?: url
            return url
        }

        /**
         * 2.9.1 may have rewritten a saved `https://` entry to `http://`. That is ambiguous with intentional
         * plain-HTTP servers, so set [JellyfinSession.preferHttps] (keep the stored URL) so connects try HTTPS
         * first and fall back to HTTP for the session only. New sign-ins that use `https://` keep that scheme.
         */
        fun migratePreferHttps(session: JellyfinSession): JellyfinSession {
            val url = session.serverUrl.trim().trimEnd('/')
            if (!url.startsWith("http://", ignoreCase = true)) return session
            return if (session.preferHttps) session else session.copy(preferHttps = true)
        }

        /** Jellyfin ids, compared the way the server writes them in either place (with or without dashes, any case). */
        fun sameServerId(a: String?, b: String?): Boolean {
            val x = a?.replace("-", "")?.trim().orEmpty()
            val y = b?.replace("-", "")?.trim().orEmpty()
            return x.isNotEmpty() && x.equals(y, ignoreCase = true)
        }

        /** The user chose not to use [httpsUrl]'s server over plain HTTP: it's as unreachable as before. */
        fun notOverHttps(httpsUrl: String, httpsErr: Throwable): MediaSourceException {
            val server = httpsUrl.toHttpUrlOrNull()?.let(::serverLabel) ?: httpsUrl
            return MediaSourceException("$server didn't answer over HTTPS", cause = httpsErr)
        }

        /** How the server [url] is named to the user: its host, and the port when it isn't HTTPS's own. */
        fun serverLabel(url: HttpUrl): String = if (url.port == 443) url.host else "${url.host}:${url.port}"

        fun bothSchemesFailed(httpsUrl: String, httpsErr: Throwable, httpUrl: String, httpErr: Throwable): MediaSourceException {
            val httpsWhy = httpsErr.message?.substringAfter("(", "")?.trimEnd(')')?.ifBlank { null }
                ?: httpsErr.message ?: "HTTPS failed"
            val httpWhy = httpErr.message?.substringAfter("(", "")?.trimEnd(')')?.ifBlank { null }
                ?: httpErr.message ?: "HTTP failed"
            val host = httpsUrl.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" } ?: httpsUrl
            return MediaSourceException(
                "Cannot reach $host over HTTPS ($httpsWhy) or HTTP ($httpWhy)",
                cause = httpsErr,
            )
        }
    }

    /**
     * Checks a stored session is still valid at its preferred probe URL (HTTPS first when requested). The token is
     * only sent once the server there proved to be the session's ([confirmServer]); another one throws
     * [ServerMismatchException], like an unreachable server.
     */
    suspend fun validate(session: JellyfinSession): Boolean =
        validateAt(preferredProbeUrl(session), session.accessToken, session.serverId)

    /**
     * Whether [accessToken] still signs in at [serverUrl]. With a [serverId], the server there is asked who it is
     * first, without the token, and the token is only sent when it is that server.
     */
    suspend fun validateAt(serverUrl: String, accessToken: String, serverId: String = ""): Boolean {
        if (serverId.isNotBlank()) confirmServer(serverUrl, serverId)
        return try {
            getRaw(serverUrl, accessToken, "Users/Me") { }
            true
        } catch (e: MediaSourceException) {
            if (e.httpCode == 401 || e.httpCode == 403) false else throw e
        }
    }

    /**
     * Asks the server at [serverUrl] for its id without a token (`System/Info/Public`, which every Jellyfin answers)
     * and throws [ServerMismatchException] unless it is [serverId]. When nothing answers at all, the network error
     * is thrown as it is.
     */
    suspend fun confirmServer(serverUrl: String, serverId: String) {
        val where = serverUrl.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" } ?: serverUrl
        val info = try {
            publicInfo(serverUrl)
        } catch (e: MediaSourceException) {
            if (e.httpCode == null) throw e
            throw ServerMismatchException("Cannot reach $where (no Jellyfin answers there: ${e.message})", e)
        } catch (e: SerializationException) {
            throw ServerMismatchException("Cannot reach $where (no Jellyfin answers there)", e)
        }
        if (!sameServerId(info.id, serverId)) {
            throw ServerMismatchException("Cannot reach $where (a different server answers there)")
        }
    }

    /**
     * Signs [session] out. Over plain HTTP as well only when [alsoPlainHttp]: by default for servers given as
     * `http://`, never sending an `https://` server's token unencrypted unless it's already used over HTTP.
     */
    suspend fun logout(session: JellyfinSession, alsoPlainHttp: Boolean = !session.serverUrl.trim().startsWith("https://", ignoreCase = true)) {
        val url = preferredProbeUrl(session)
        runCatching { postRaw(url, session.accessToken, "Sessions/Logout", null) { } }
        if (!alsoPlainHttp) return
        plainHttpUrl(url)?.let { http ->
            if (http != url) runCatching { postRaw(http, session.accessToken, "Sessions/Logout", null) { } }
        }
    }

    private fun sessionFrom(preferredUrl: String, info: PublicSystemInfo, auth: AuthenticationResult): JellyfinSession {
        val user = auth.user ?: throw MediaSourceException("Server returned no user")
        val token = auth.accessToken ?: throw MediaSourceException("Server returned no access token")
        return JellyfinSession(
            serverUrl = preferredUrl,
            serverName = info.serverName ?: preferredUrl,
            serverId = auth.serverId ?: info.id ?: "",
            userId = user.id,
            userName = user.name ?: "",
            accessToken = token,
            lanAddress = LanHosts.hintFor(preferredUrl).orEmpty(),
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
