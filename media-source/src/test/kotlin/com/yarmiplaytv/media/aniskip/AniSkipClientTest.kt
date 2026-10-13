package com.yarmiplaytv.media.aniskip

import com.yarmiplaytv.media.MediaSourceException
import com.yarmiplaytv.media.SkipSegment
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class AniSkipClientTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.requestUrl!!.encodedPath) {
                    "/v2/skip-times/21/1" -> MockResponse().setHeader("Content-Type", "application/json").setBody(
                        """{"found":true,"results":[
                        |{"interval":{"startTime":1387.9,"endTime":1500},"skipType":"ed","skipId":"b","episodeLength":1440.4},
                        |{"interval":{"startTime":28.7,"endTime":118.7},"skipType":"op","skipId":"a","episodeLength":1439.9},
                        |{"interval":{"startTime":186.2,"endTime":276.2},"skipType":"mixed-op","skipId":"c","episodeLength":1440}
                        |],"message":"Successfully found skip times","statusCode":200}""".trimMargin(),
                    )
                    "/v2/skip-times/21/2" -> MockResponse().setHeader("Content-Type", "application/json").setBody(
                        """{"found":true,"results":[{"interval":{"startTime":30,"endTime":120},"skipType":"op","skipId":"d","episodeLength":1300}]}""",
                    )
                    "/v2/skip-times/21/3" -> MockResponse().setResponseCode(500)
                    else -> MockResponse().setResponseCode(404).setBody("""{"found":false,"results":[]}""")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client() = AniSkipClient(apiUrl = server.url("/v2").toString())

    @Test
    fun `asks for the opening and ending with the file's length, and gives them in order`() = runBlocking {
        val segments = client().skipTimes(21, 1, 1440.0)
        assertEquals(
            listOf(SkipSegment(SkipSegment.Kind.OPENING, 28.7, 118.7), SkipSegment(SkipSegment.Kind.ENDING, 1387.9, 1440.0)),
            segments,
        )
        val url = requests.single().requestUrl!!
        assertEquals(listOf("op", "ed"), url.queryParameterValues("types[]"))
        assertEquals("1440.000", url.queryParameter("episodeLength"))
    }

    @Test
    fun `times measured on a file of another length are left out`() = runBlocking {
        assertEquals(emptyList<SkipSegment>(), client().skipTimes(21, 2, 1440.0))
    }

    @Test
    fun `nothing known is an empty answer, and a failing AniSkip an error`() = runBlocking {
        assertEquals(emptyList<SkipSegment>(), client().skipTimes(99, 1, 1440.0))
        try {
            client().skipTimes(21, 3, 1440.0)
            fail()
        } catch (e: MediaSourceException) {
            assertEquals(500, e.httpCode)
        }
    }

    /** The real AniSkip, only with YARMIPLAYTV_LIVE_TESTS set: One Piece (MyAnimeList 21) episode 1. */
    @Test
    fun `the live AniSkip knows One Piece episode 1`() = runBlocking {
        assumeTrue("live tests off", !System.getenv("YARMIPLAYTV_LIVE_TESTS").isNullOrEmpty())
        val segments = AniSkipClient().skipTimes(21, 1, 1440.0)
        assertTrue(segments.toString(), segments.any { it.kind == SkipSegment.Kind.OPENING })
    }
}
