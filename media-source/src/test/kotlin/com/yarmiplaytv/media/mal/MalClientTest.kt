package com.yarmiplaytv.media.mal

import com.yarmiplaytv.media.MediaSourceException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class MalClientTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<Pair<RecordedRequest, String>>()
    private var accessToken = "access-1"
    private var refreshes = 0
    private val saved = mutableListOf<MalTokens?>()
    private var clock = 1_000_000L

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                requests += request to body
                val path = request.requestUrl!!.encodedPath
                return when {
                    path == "/v1/oauth2/token" && "grant_type=authorization_code" in body ->
                        json("""{"token_type":"Bearer","expires_in":3600,"access_token":"access-1","refresh_token":"refresh-1"}""")
                    path == "/v1/oauth2/token" && "grant_type=refresh_token" in body -> {
                        if ("refresh_token=revoked" in body) return MockResponse().setResponseCode(400)
                        refreshes++
                        accessToken = "access-${refreshes + 1}"
                        json("""{"token_type":"Bearer","expires_in":3600,"access_token":"$accessToken","refresh_token":"refresh-${refreshes + 1}"}""")
                    }
                    request.getHeader("Authorization") != "Bearer $accessToken" -> MockResponse().setResponseCode(401)
                    path == "/v2/users/@me" -> json("""{"id":7,"name":"ana","picture":"https://cdn.example/ana.jpg"}""")
                    path == "/v2/anime" -> json("""{"data":[{"node":{"id":52991,"title":"Sousou no Frieren","num_episodes":28,"media_type":"tv","start_date":"2023-09-29"}}]}""")
                    path == "/v2/anime/52991" -> json(
                        """{"id":52991,"title":"Sousou no Frieren","num_episodes":28,"media_type":"tv","related_anime":[{"node":{"id":59978,"title":"Sousou no Frieren 2nd Season"},"relation_type":"sequel"}],
                        |"my_list_status":{"status":"watching","score":9,"num_episodes_watched":3,"is_rewatching":false}}""".trimMargin(),
                    )
                    path == "/v2/anime/52991/my_list_status" && request.method == "PATCH" ->
                        json("""{"status":"watching","score":9,"num_episodes_watched":4,"is_rewatching":false,"start_date":"2026-10-01"}""")
                    path == "/v2/users/@me/animelist" ->
                        json("""{"data":[{"node":{"id":52991,"title":"Sousou no Frieren","num_episodes":28},"list_status":{"status":"watching","num_episodes_watched":3}}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun client() = MalClient(
        "client-id",
        authUrl = server.url("/").toString().trimEnd('/'),
        apiUrl = server.url("/v2").toString(),
        now = { clock },
    ).apply { onTokens = { saved += it } }

    @Test
    fun `the sign-in page uses PKCE plain with the verifier as challenge`() {
        val url = client().authorizeUrl("st4te", "v".repeat(64)).toHttpUrl()
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("client-id", url.queryParameter("client_id"))
        assertEquals("st4te", url.queryParameter("state"))
        assertEquals("v".repeat(64), url.queryParameter("code_challenge"))
        assertEquals("plain", url.queryParameter("code_challenge_method"))
        assertEquals(MalClient.REDIRECT_URI, url.queryParameter("redirect_uri"))
        assertEquals("/v1/oauth2/authorize", url.encodedPath)
        val verifier = MalClient.newVerifier()
        assertTrue(verifier.length in 43..128 && verifier.all { it.isLetterOrDigit() || it in "-._~" })
    }

    @Test
    fun `signing in exchanges the code, and calls send the bearer token`() = runBlocking {
        val c = client()
        val tokens = c.exchange("the-code", "the-verifier")
        assertEquals("access-1", tokens.accessToken)
        assertEquals(clock + 3_600_000, tokens.expiresAtMillis)
        assertEquals(listOf<MalTokens?>(tokens), saved)
        val body = requests.single { it.first.requestUrl!!.encodedPath == "/v1/oauth2/token" }.second
        assertTrue(body, "code=the-code" in body && "code_verifier=the-verifier" in body && "client_id=client-id" in body)
        assertTrue("no client secret", "client_secret" !in body)
        assertEquals("ana", c.me().name)
    }

    @Test
    fun `an expired token is refreshed first, and a 401 refreshes once and retries`() = runBlocking {
        val c = client()
        c.exchange("code", "verifier")
        clock += 3_600_000
        assertEquals("ana", c.me().name)
        assertEquals(1, refreshes)
        // The server revoked the access token early.
        accessToken = "access-other"
        try {
            c.me()
        } catch (e: MediaSourceException) {
            // access-other is never handed out by refresh, so the retry is refused too.
        }
        assertEquals(2, refreshes)
        assertEquals("refresh-3", saved.last()!!.refreshToken)
    }

    @Test
    fun `a refresh token that stopped working signs out`() = runBlocking {
        val c = client()
        c.tokens = MalTokens("old", "revoked", clock - 1)
        try {
            c.me()
            fail()
        } catch (e: MediaSourceException) {
            assertEquals(401, e.httpCode)
        }
        assertNull(c.tokens)
        assertEquals(listOf<MalTokens?>(null), saved)
    }

    @Test
    fun `search, details, the list, and updates as form fields`() = runBlocking {
        val c = client()
        c.exchange("code", "verifier")
        assertEquals(emptyList<MalAnime>(), c.search("ab"))
        assertEquals(52991, c.search("Frieren").single().id)
        val anime = c.anime(52991)
        assertEquals(listOf(59978 to "sequel"), anime.relatedAnime.map { it.node.id to it.relationType })
        assertEquals(3, anime.myListStatus!!.watchedEpisodes)
        assertEquals(MalStatus.WATCHING, anime.myListStatus!!.statusValue)
        assertEquals(52991, c.myList(MalStatus.WATCHING).single().first.id)

        val now = c.updateStatus(52991, MalListUpdate(status = MalStatus.WATCHING, watchedEpisodes = 4, startDate = "2026-10-01"))
        assertEquals(4, now.watchedEpisodes)
        val patch = requests.last { it.first.method == "PATCH" }.second
        assertEquals("status=watching&num_watched_episodes=4&start_date=2026-10-01", patch)
    }
}
