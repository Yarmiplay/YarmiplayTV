package com.yarmiplaytv.media.mal

import com.yarmiplaytv.media.MediaSourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * MyAnimeList API v2 client. Signs in with OAuth 2 and PKCE ("plain", the only method MyAnimeList supports, with no
 * client secret), refreshes the access token when it runs out, and reads and updates the user's anime list.
 * Only the API is used, never the website. [clientId] is the app's registered id: it's never logged or shown.
 */
class MalClient(
    private val clientId: String,
    http: OkHttpClient? = null,
    private val authUrl: String = "https://myanimelist.net",
    private val apiUrl: String = "https://api.myanimelist.net/v2",
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val http: OkHttpClient = http ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val refreshLock = Mutex()

    /** The signed-in user's tokens; null when signed out. */
    @Volatile var tokens: MalTokens? = null

    /** Told about new tokens (after signing in or a refresh) and null when the refresh token stopped working. */
    @Volatile var onTokens: (MalTokens?) -> Unit = {}

    /** Whether this build has a Client ID (it comes from the build, never from the repository). */
    val configured: Boolean get() = clientId.isNotBlank()

    /** The page where the user signs in on MyAnimeList itself; it comes back to [redirectUri] with a code. */
    fun authorizeUrl(state: String, verifier: String, redirectUri: String = REDIRECT_URI): String =
        "$authUrl/v1/oauth2/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("state", state)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("code_challenge", verifier)
            .addQueryParameter("code_challenge_method", "plain")
            .build().toString()

    /** Trades the code from the sign-in page for tokens, and keeps them. */
    suspend fun exchange(code: String, verifier: String, redirectUri: String = REDIRECT_URI): MalTokens =
        token(
            "grant_type" to "authorization_code",
            "code" to code,
            "code_verifier" to verifier,
            "redirect_uri" to redirectUri,
        ).also(::keep)

    private suspend fun refresh(): MalTokens {
        val current = tokens ?: throw signedOut()
        return try {
            token("grant_type" to "refresh_token", "refresh_token" to current.refreshToken).also(::keep)
        } catch (e: MediaSourceException) {
            if (e.httpCode == 400 || e.httpCode == 401) {
                tokens = null
                onTokens(null)
                throw signedOut()
            }
            throw e
        }
    }

    private fun keep(t: MalTokens) {
        tokens = t
        onTokens(t)
    }

    private suspend fun token(vararg fields: Pair<String, String>): MalTokens {
        val body = FormBody.Builder().add("client_id", clientId).apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val text = execute(Request.Builder().url("$authUrl/v1/oauth2/token").post(body))
        val r = json.decodeFromString(TokenResponse.serializer(), text)
        return MalTokens(r.accessToken, r.refreshToken, now() + r.expiresIn * 1000)
    }

    suspend fun me(): MalUser = json.decodeFromString(MalUser.serializer(), call { api("users/@me") { addQueryParameter("fields", "picture") } })

    /**
     * Up to [limit] anime whose titles match [query] (MyAnimeList needs at least 3 characters). Works signed out
     * too, as public data.
     */
    suspend fun search(query: String, limit: Int = 10): List<MalAnime> {
        val q = searchQuery(query)
        if (q.length < 3) return emptyList()
        return json.decodeFromString(MalPage.serializer(), publicCall {
            api("anime") {
                addQueryParameter("q", q)
                addQueryParameter("limit", limit.coerceIn(1, 100).toString())
                addQueryParameter("fields", LIST_FIELDS)
            }
        }).data.map { it.node }
    }

    /** Anime [id]'s details; signed in, with your list entry. Works signed out too, as public data. */
    suspend fun anime(id: Int): MalAnime =
        json.decodeFromString(MalAnime.serializer(), publicCall { api("anime/$id") { addQueryParameter("fields", DETAIL_FIELDS) } })

    /** Changes your list entry for anime [id]; returns the entry as it is now. */
    suspend fun updateStatus(id: Int, update: MalListUpdate): MalListStatus {
        val form = FormBody.Builder().apply { update.formFields().forEach { (k, v) -> add(k, v) } }.build()
        return json.decodeFromString(MalListStatus.serializer(), call(Request.Builder().url(api("anime/$id/my_list_status")).patch(form)))
    }

    suspend fun deleteStatus(id: Int) {
        call(Request.Builder().url(api("anime/$id/my_list_status")).delete())
    }

    /** Your list entries with [status] (e.g. what you're watching now), most recently updated first. */
    suspend fun myList(status: MalStatus, limit: Int = 100): List<Pair<MalAnime, MalListStatus>> =
        json.decodeFromString(MalPage.serializer(), call {
            api("users/@me/animelist") {
                addQueryParameter("status", status.api)
                addQueryParameter("sort", "list_updated_at")
                addQueryParameter("limit", limit.coerceIn(1, 1000).toString())
                addQueryParameter("fields", "list_status,$LIST_FIELDS")
            }
        }).data.map { it.node to (it.listStatus ?: MalListStatus()) }

    private fun api(path: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        "$apiUrl/$path".toHttpUrl().newBuilder().apply(query).build()

    private suspend fun call(url: () -> HttpUrl): String = call(Request.Builder().url(url()))

    /**
     * Public data (titles, episodes, related anime): as the signed-in user when there is one, else with the app's
     * Client ID alone, which MyAnimeList allows for public data.
     */
    private suspend fun publicCall(url: () -> HttpUrl): String {
        if (tokens != null) return call(url)
        if (!configured) throw signedOut()
        return execute(Request.Builder().url(url()).header("X-MAL-CLIENT-ID", clientId))
    }

    /** Runs an API request as the signed-in user, refreshing the access token first when it ran out, or once on a 401. */
    private suspend fun call(request: Request.Builder): String {
        var t = tokens ?: throw signedOut()
        if (t.expiresAtMillis - now() < 60_000) t = refreshLock.withLock { tokens?.takeIf { it.expiresAtMillis - now() >= 60_000 } ?: refresh() }
        return try {
            execute(request.header("Authorization", "Bearer ${t.accessToken}"))
        } catch (e: MediaSourceException) {
            if (e.httpCode != 401) throw e
            val fresh = refreshLock.withLock { tokens?.takeIf { it.accessToken != t.accessToken } ?: refresh() }
            execute(request.header("Authorization", "Bearer ${fresh.accessToken}"))
        }
    }

    private suspend fun execute(builder: Request.Builder): String = withContext(Dispatchers.IO) {
        val request = builder.header("Accept", "application/json").build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val msg = when (response.code) {
                        401 -> "Not signed in to MyAnimeList (401)"
                        403 -> "MyAnimeList refused the request (403)"
                        404 -> "Not found on MyAnimeList (404)"
                        429 -> "MyAnimeList is busy; trying again later (429)"
                        else -> "MyAnimeList answered ${response.code}"
                    }
                    throw MediaSourceException(msg, response.code)
                }
                text
            }
        } catch (e: IOException) {
            throw MediaSourceException("Cannot reach MyAnimeList (${e.message})", cause = e)
        }
    }

    private fun signedOut() = MediaSourceException("Not signed in to MyAnimeList", 401)

    companion object {
        /** Where MyAnimeList sends the sign-in back to; registered with the app on MyAnimeList. */
        const val CALLBACK_PORT = 47821
        const val REDIRECT_URI = "http://localhost:$CALLBACK_PORT/mal-callback"
        private const val LIST_FIELDS = "id,title,alternative_titles,main_picture,start_date,media_type,num_episodes,status"
        private const val DETAIL_FIELDS = "$LIST_FIELDS,mean,related_anime,my_list_status"
        private const val VERIFIER_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        /** [query] as MyAnimeList takes it: at most 64 characters, cut at a word boundary rather than mid-word. */
        fun searchQuery(query: String): String {
            val q = query.trim().replace(Regex("\\s+"), " ")
            if (q.length <= MAX_QUERY) return q
            val cut = q.take(MAX_QUERY + 1).lastIndexOf(' ').takeIf { it >= 3 } ?: MAX_QUERY
            return q.take(cut).trim().trimEnd(',', ':', '-', ' ')
        }

        private const val MAX_QUERY = 64

        /** A PKCE code verifier (also the challenge, with the "plain" method): 64 unreserved characters. */
        fun newVerifier(random: SecureRandom = SecureRandom()): String = String(CharArray(64) { VERIFIER_CHARS[random.nextInt(VERIFIER_CHARS.length)] })

        /** A random `state` that ties the sign-in page's answer to this sign-in. */
        fun newState(random: SecureRandom = SecureRandom()): String = String(CharArray(24) { VERIFIER_CHARS[random.nextInt(62)] })
    }
}
