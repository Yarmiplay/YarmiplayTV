package com.yarmiplaytv.support

import android.util.Log
import com.yarmiplaytv.media.plex.PlexSession
import com.yarmiplaytv.media.plex.PlexSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.net.InetAddress

/**
 * A Plex server with one movie library, signed in to directly (no plex.tv). "Paper Boats (2018).mkv"
 * is the same file as on [FakeJellyfin], so playback is reported to both.
 */
class FakePlex(
    port: Int = PORT,
    machineId: String = "fake-plex",
    serverName: String = "Plex Server",
) : AutoCloseable {
    private data class Movie(val ratingKey: String, val title: String, val year: Int, val file: String, val size: Long, val color: Int)

    private val movies = listOf(
        Movie("101", "Lantern Festival", 2020, "/data/Films/Lantern Festival (2020).mkv", 1_288_490_188, 0xFFF9A825.toInt()),
        Movie("102", "Paper Boats", 2018, "/data/Films/Paper Boats (2018).mkv", 734_003_200, 0xFFC62828.toInt()),
    )
    private val byKey = movies.associateBy { it.ratingKey }

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                respond(request).also { Log.i("FakePlex", "${request.method} ${request.path} -> ${it.status}") }
        }
        start(InetAddress.getByName("127.0.0.1"), port)
    }

    val url: String = "http://127.0.0.1:$port"
    val session = PlexSession(
        serverUrl = url,
        serverName = serverName,
        machineId = machineId,
        userName = "viewer",
        serverToken = "fake-plex-token",
        accountToken = "fake-plex-account",
    )
    val key: String = PlexSource.keyOf(session)

    override fun close() = server.shutdown()

    private fun respond(request: RecordedRequest): MockResponse {
        val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
        val path = url.encodedPath
        return when {
            path == "/identity" -> json(container { put("machineIdentifier", session.machineId) })
            path == "/library/sections" -> json(container {
                putJsonArray("Directory") {
                    add(buildJsonObject { put("key", "1"); put("title", "Films"); put("type", "movie"); put("thumb", "/library/sections/1/thumb") })
                }
            })
            path == "/library/sections/1/all" || path == "/library/recentlyAdded" -> json(metadata(movies))
            path == "/library/onDeck" -> json(metadata(emptyList()))
            path.startsWith("/library/metadata/") -> json(metadata(listOfNotNull(byKey[path.removePrefix("/library/metadata/")])))
            path == "/hubs/search" -> {
                val term = url.queryParameter("query").orEmpty()
                val hits = movies.filter { it.title.contains(term, ignoreCase = true) }
                json(container { putJsonArray("Hub") { add(buildJsonObject { put("type", "movie"); put("Metadata", JsonArray(hits.map(::dto))) }) } })
            }
            path == "/photo/:/transcode" -> {
                val thumb = url.queryParameter("url").orEmpty()
                val color = movies.firstOrNull { thumb == thumbOf(it) }?.color ?: 0xFF455A64.toInt()
                MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(fakePoster(color, portrait = true)))
            }
            path == "/:/timeline" || path == "/:/scrobble" -> MockResponse().setResponseCode(200)
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun thumbOf(movie: Movie) = "/library/metadata/${movie.ratingKey}/thumb"

    private fun container(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        putJsonObject("MediaContainer", block)
    }

    private fun metadata(list: List<Movie>) = container {
        put("size", list.size)
        put("totalSize", list.size)
        put("Metadata", JsonArray(list.map(::dto)))
    }

    private fun dto(movie: Movie): JsonObject = buildJsonObject {
        put("ratingKey", movie.ratingKey)
        put("type", "movie")
        put("title", movie.title)
        put("year", movie.year)
        put("duration", 5_400_000L)
        put("thumb", thumbOf(movie))
        putJsonArray("Media") {
            add(buildJsonObject {
                put("duration", 5_400_000L)
                putJsonArray("Part") {
                    add(buildJsonObject {
                        put("key", "/library/parts/${movie.ratingKey}/1/file.mkv")
                        put("file", movie.file)
                        put("size", movie.size)
                    })
                }
            })
        }
    }

    private fun json(body: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())

    companion object {
        const val PORT = 18400
    }
}
