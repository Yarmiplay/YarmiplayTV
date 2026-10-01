package com.syncplaytv.support

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import com.syncplaytv.media.jellyfin.JellyfinSession
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
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * A Jellyfin server with a small fixed library (two shows, two movies) and generated posters, so the
 * browse screens look the same on every run. `/hang/...` never answers and `/missing/...` is a 404,
 * for the player's loading and failed states.
 */
class FakeJellyfin(port: Int = PORT) : AutoCloseable {
    private data class Item(
        val id: String,
        val name: String,
        val type: String,
        val collectionType: String? = null,
        val parent: String? = null,
        val seriesName: String? = null,
        val seriesId: String? = null,
        val index: Int? = null,
        val season: Int? = null,
        val year: Int? = null,
        val overview: String? = null,
        val path: String? = null,
        val played: Boolean = false,
        val color: Int,
    )

    private val items = listOf(
        Item("lib-shows", "Shows", "CollectionFolder", collectionType = "tvshows", color = 0xFF3D5AFE.toInt()),
        Item("lib-movies", "Movies", "CollectionFolder", collectionType = "movies", color = 0xFFFF6D00.toInt()),
        Item("series-orbit", "Orbit Station", "Series", parent = "lib-shows", year = 2021, overview = "A crew keeps an old space station running.", color = 0xFF00897B.toInt()),
        Item("series-harbor", "Harbor Lights", "Series", parent = "lib-shows", year = 2019, overview = "A small coastal town and its lighthouse keepers.", color = 0xFF6A1B9A.toInt()),
        Item("ep-orbit-1", "Docking", "Episode", parent = "series-orbit", seriesName = "Orbit Station", seriesId = "series-orbit", index = 1, season = 1,
            overview = "The new engineer arrives.", path = "/media/Orbit Station/Orbit Station S01E01.mkv", played = true, color = 0xFF26A69A.toInt()),
        Item("ep-orbit-2", "Blackout", "Episode", parent = "series-orbit", seriesName = "Orbit Station", seriesId = "series-orbit", index = 2, season = 1,
            overview = "The station loses power.", path = "/media/Orbit Station/Orbit Station S01E02.mkv", color = 0xFF4DB6AC.toInt()),
        Item("movie-paper", "Paper Boats", "Movie", parent = "lib-movies", year = 2018, overview = "Two kids race paper boats down the river.",
            path = "/media/Movies/Paper Boats (2018).mkv", color = 0xFFC62828.toInt()),
        Item("movie-north", "North Wind", "Movie", parent = "lib-movies", year = 2022, overview = "A sled team crosses the tundra.",
            path = "/media/Movies/North Wind (2022).mkv", played = true, color = 0xFF1565C0.toInt()),
    )
    private val byId = items.associateBy { it.id }

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                respond(request).also { Log.i("FakeJellyfin", "${request.method} ${request.path} -> ${it.status}") }
        }
        start(InetAddress.getByName("127.0.0.1"), port)
    }

    val url: String = "http://127.0.0.1:$port"
    val session = JellyfinSession(
        serverUrl = url,
        serverName = "Screenshot Server",
        serverId = "fake-server",
        userId = "fake-user",
        userName = "viewer",
        accessToken = "fake-token",
    )

    /** A stream that never starts (the player stays in "Loading…"). */
    val hangingStreamUrl = "$url/hang/Orbit%20Station%20S01E03.mkv"
    /** A stream the server refuses (the player shows "Couldn't play this file"). */
    val missingStreamUrl = "$url/missing/Orbit%20Station%20S01E04.mkv"

    override fun close() = server.shutdown()

    private fun respond(request: RecordedRequest): MockResponse {
        val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
        val path = url.encodedPath
        val q = { name: String -> url.queryParameter(name) }
        return when {
            path.startsWith("/hang/") -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            path.startsWith("/missing/") -> MockResponse().setResponseCode(404)
            path == "/System/Info/Public" -> json(buildJsonObject { put("ServerName", session.serverName); put("Id", session.serverId); put("Version", "10.10.7") })
            path == "/Users/Me" -> json(buildJsonObject { put("Id", session.userId); put("Name", session.userName) })
            path == "/QuickConnect/Enabled" -> MockResponse().setBody("false")
            path == "/UserViews" -> json(result(items.filter { it.type == "CollectionFolder" }))
            path == "/UserItems/Resume" -> json(result(listOf(byId.getValue("ep-orbit-2"))))
            path.startsWith("/Items/") && path.endsWith("/Images/Primary") -> {
                val id = path.removePrefix("/Items/").removeSuffix("/Images/Primary")
                val item = byId[id] ?: return MockResponse().setResponseCode(404)
                MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(poster(item)))
            }
            path == "/Items" -> json(result(query(q)))
            path.startsWith("/Shows/") && path.endsWith("/Episodes") -> json(result(items.filter { it.type == "Episode" && it.seriesId == path.split('/')[2] }))
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun query(q: (String) -> String?): List<Item> {
        q("ids")?.let { ids -> return ids.split(',').mapNotNull(byId::get) }
        val types = q("includeItemTypes")?.split(',')?.toSet()
        var list = items.filter { it.type != "CollectionFolder" }
        q("parentId")?.let { parent ->
            list = if (q("recursive") == "true") list.filter { it.parent == parent || byId[it.parent]?.parent == parent } else list.filter { it.parent == parent }
        }
        if (types != null) list = list.filter { it.type in types }
        q("searchTerm")?.let { term -> list = list.filter { it.name.contains(term, ignoreCase = true) || it.seriesName?.contains(term, ignoreCase = true) == true } }
        if (q("sortBy") == "DateCreated") list = list.reversed()
        return list.take(q("limit")?.toIntOrNull() ?: Int.MAX_VALUE)
    }

    private fun result(list: List<Item>) = buildJsonObject {
        put("Items", JsonArray(list.map(::dto)))
        put("TotalRecordCount", list.size)
    }

    private fun dto(item: Item): JsonObject = buildJsonObject {
        put("Id", item.id)
        put("Name", item.name)
        put("Type", item.type)
        put("IsFolder", item.type in setOf("CollectionFolder", "Series", "Season"))
        item.collectionType?.let { put("CollectionType", it) }
        item.overview?.let { put("Overview", it) }
        item.year?.let { put("ProductionYear", it) }
        item.index?.let { put("IndexNumber", it) }
        item.season?.let { put("ParentIndexNumber", it) }
        item.seriesName?.let { put("SeriesName", it) }
        item.seriesId?.let { put("SeriesId", it) }
        if (item.path != null) {
            put("Path", item.path)
            put("RunTimeTicks", 1_440L * 10_000_000L)
            putJsonArray("MediaSources") {
                add(buildJsonObject {
                    put("Id", item.id)
                    put("Path", item.path)
                    put("Protocol", "File")
                    put("Size", 734_003_200L)
                    put("RunTimeTicks", 1_440L * 10_000_000L)
                })
            }
        }
        putJsonObject("ImageTags") { put("Primary", "tag-${item.id}") }
        putJsonObject("UserData") { put("Played", item.played); put("PlaybackPositionTicks", 0) }
    }

    private fun json(body: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())

    /** A diagonal two-tone gradient with a circle, unique per item and identical on every run. */
    private fun poster(item: Item): ByteArray {
        val portrait = item.type in setOf("Series", "Movie")
        val (w, h) = if (portrait) 300 to 450 else 480 to 270
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val darker = Color.rgb(Color.red(item.color) / 3, Color.green(item.color) / 3, Color.blue(item.color) / 3)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), item.color, darker, Shader.TileMode.CLAMP) })
        canvas.drawCircle(w * 0.7f, h * 0.35f, minOf(w, h) * 0.18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 255, 255, 255) })
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    companion object {
        const val PORT = 18096
    }
}
