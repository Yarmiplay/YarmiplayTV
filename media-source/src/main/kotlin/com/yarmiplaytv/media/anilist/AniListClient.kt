package com.yarmiplaytv.media.anilist

import com.yarmiplaytv.media.MediaSourceException
import com.yarmiplaytv.media.SharedHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AniList GraphQL v2 client (`https://graphql.anilist.co`).
 * Uses OAuth 2 Implicit Grant (`response_type=token`) so no client secret is ever embedded in
 * open-source client builds; tokens stay strictly local and are cleared on sign-out.
 */
class AniListClient(
    private val clientId: String,
    http: OkHttpClient? = null,
    private val authUrl: String = "https://anilist.co/api/v2/oauth/authorize",
    private val apiUrl: String = "https://graphql.anilist.co",
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val http: OkHttpClient = http ?: SharedHttp.base.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    var tokens: AniListTokens? = null

    @Volatile
    var onExpired: () -> Unit = {}

    val configured: Boolean get() = clientId.isNotBlank()

    /** Implicit Grant authorize URL (no client secret required in client builds). */
    fun authorizeUrl(redirectUri: String? = null): String =
        authUrl.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("response_type", "token")
            .apply { redirectUri?.takeIf { it.isNotBlank() }?.let { addQueryParameter("redirect_uri", it) } }
            .build().toString()

    /** Parses `#access_token=...&expires_in=...` (or a full redirect URL containing it) from AniList Implicit Grant. */
    fun parseFragment(fragment: String): AniListTokens? {
        val trimmed = fragment.trim()
        val raw = when {
            '#' in trimmed -> trimmed.substringAfter('#')
            '?' in trimmed -> trimmed.substringAfter('?')
            else -> trimmed
        }
        if (raw.isEmpty()) return null
        val params = raw.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null
            else part.substring(0, idx) to java.net.URLDecoder.decode(part.substring(idx + 1), "UTF-8")
        }.toMap()
        val token = params["access_token"]?.takeIf { it.isNotBlank() } ?: return null
        val expiresIn = params["expires_in"]?.toLongOrNull() ?: DEFAULT_EXPIRES_SECONDS
        return AniListTokens(token, now() + expiresIn * 1000L)
    }

    suspend fun viewer(): AniListAccount {
        val t = tokens ?: throw signedOut()
        val data = graphql(VIEWER_QUERY, JsonObject(emptyMap()), requireAuth = true)
        val v = data["Viewer"]?.jsonObject ?: throw MediaSourceException("AniList did not return viewer profile")
        return AniListAccount(
            tokens = t,
            userId = v.int("id") ?: 0,
            userName = v.str("name") ?: "AniList user",
            avatarUrl = v["avatar"]?.jsonObject?.str("medium"),
            scoreFormat = v["mediaListOptions"]?.jsonObject?.str("scoreFormat") ?: AniListScoreFormat.POINT_10.api,
        )
    }

    /** Searches anime on AniList (works signed out as public GraphQL too; signed in includes `mediaListEntry`). */
    suspend fun search(query: String, limit: Int = 15): List<AniListAnime> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val vars = buildJsonObject {
            put("search", q)
            put("perPage", limit.coerceIn(1, 50))
        }
        val data = graphql(SEARCH_QUERY, vars, requireAuth = false)
        val media = data["Page"]?.jsonObject?.get("media")?.jsonArray ?: return emptyList()
        return media.mapNotNull { parseMedia(it.jsonObject) }
    }

    /** Full details for one anime including prequel/sequel relations and current list entry. */
    suspend fun anime(id: Int): AniListAnime {
        val vars = buildJsonObject { put("id", id) }
        val data = graphql(DETAIL_QUERY, vars, requireAuth = false)
        val obj = data["Media"]?.jsonObject ?: throw MediaSourceException("Anime $id not found on AniList", 404)
        return parseMedia(obj) ?: throw MediaSourceException("Invalid AniList media response")
    }

    /** The signed-in user's list entries for [status], most recently updated first. */
    suspend fun myList(userId: Int, status: AniListStatus): List<AniListAnime> {
        val vars = buildJsonObject {
            put("userId", userId)
            put("status", status.api)
        }
        val data = graphql(MY_LIST_QUERY, vars, requireAuth = true)
        val lists = data["MediaListCollection"]?.jsonObject?.get("lists")?.jsonArray ?: return emptyList()
        val out = ArrayList<AniListAnime>()
        for (listEl in lists) {
            val entries = listEl.jsonObject["entries"]?.jsonArray ?: continue
            for (entryEl in entries) {
                val eo = entryEl.jsonObject
                val mediaObj = eo["media"]?.jsonObject ?: continue
                val parsed = parseMedia(mediaObj) ?: continue
                out += parsed.copy(listEntry = parseEntry(eo))
            }
        }
        return out
    }

    suspend fun saveEntry(mediaId: Int, update: AniListUpdate): AniListEntryStatus {
        val vars = buildJsonObject {
            put("mediaId", mediaId)
            update.status?.let { put("status", it.api) }
            update.progress?.let { put("progress", it.coerceAtLeast(0)) }
            update.score?.let { put("score", it.coerceAtLeast(0.0)) }
            update.repeat?.let { put("repeat", it.coerceAtLeast(0)) }
            update.startedAt?.let { put("startedAt", encodeDate(it)) }
            update.completedAt?.let { put("completedAt", encodeDate(it)) }
        }
        val data = graphql(SAVE_ENTRY_MUTATION, vars, requireAuth = true)
        val saved = data["SaveMediaListEntry"]?.jsonObject
            ?: throw MediaSourceException("AniList did not return the updated entry")
        return parseEntry(saved)
    }

    suspend fun deleteEntry(entryId: Int) {
        val vars = buildJsonObject { put("id", entryId) }
        graphql(DELETE_ENTRY_MUTATION, vars, requireAuth = true)
    }

    private suspend fun graphql(query: String, variables: JsonObject, requireAuth: Boolean): JsonObject = withContext(Dispatchers.IO) {
        val tok = tokens
        if (requireAuth && tok == null) throw signedOut()
        val payload = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString()
        val req = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .apply { tok?.let { header("Authorization", "Bearer ${it.accessToken}") } }
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        try {
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (res.code == 401) {
                    if (tok != null) {
                        tokens = null
                        onExpired()
                    }
                    throw signedOut()
                }
                if (!res.isSuccessful) {
                    val msg = when (res.code) {
                        404 -> "Not found on AniList (404)"
                        429 -> "AniList is busy; trying again later (429)"
                        else -> "AniList answered ${res.code}"
                    }
                    throw MediaSourceException(msg, res.code)
                }
                val root = json.parseToJsonElement(text).jsonObject
                val errors = root["errors"] as? JsonArray
                if (!errors.isNullOrEmpty()) {
                    val firstMsg = errors.firstOrNull()?.jsonObject?.str("message") ?: "AniList error"
                    throw MediaSourceException(firstMsg)
                }
                root["data"]?.jsonObject ?: throw MediaSourceException("Empty AniList response")
            }
        } catch (e: IOException) {
            throw MediaSourceException("Cannot reach AniList (${e.message})", cause = e)
        }
    }

    private fun parseMedia(o: JsonObject): AniListAnime? {
        val id = o.int("id") ?: return null
        val titleObj = o["title"]?.jsonObject
        val romaji = titleObj?.str("romaji")
        val english = titleObj?.str("english")
        val native = titleObj?.str("native")
        val preferred = titleObj?.str("userPreferred") ?: romaji ?: english ?: native ?: "Anime #$id"
        val synonyms = (o["synonyms"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() } }.orEmpty()
        val rels = (o["relations"]?.jsonObject?.get("edges") as? JsonArray)?.mapNotNull { edgeEl ->
            val eo = edgeEl.jsonObject
            val node = eo["node"]?.jsonObject ?: return@mapNotNull null
            if (node.str("type") != null && node.str("type") != "ANIME") return@mapNotNull null
            val child = parseMedia(node) ?: return@mapNotNull null
            AniListRelation(eo.str("relationType").orEmpty(), child)
        }.orEmpty()
        val entry = o["mediaListEntry"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::parseEntry)
        return AniListAnime(
            id = id,
            idMal = o.int("idMal"),
            title = preferred,
            englishTitle = english?.takeIf { it != preferred },
            nativeTitle = native?.takeIf { it != preferred },
            synonyms = (listOfNotNull(romaji?.takeIf { it != preferred }) + synonyms).distinct(),
            coverUrl = o["coverImage"]?.jsonObject?.str("medium"),
            seasonYear = o.int("seasonYear") ?: o["startDate"]?.jsonObject?.int("year"),
            format = o.str("format"),
            episodes = o.int("episodes") ?: 0,
            status = o.str("status"),
            averageScore = o.int("averageScore"),
            relations = rels,
            listEntry = entry,
        )
    }

    private fun parseEntry(o: JsonObject): AniListEntryStatus = AniListEntryStatus(
        id = o.int("id") ?: 0,
        status = o.str("status"),
        score = o["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        progress = o.int("progress") ?: 0,
        repeat = o.int("repeat") ?: 0,
        startedAt = o["startedAt"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::parseDate),
        completedAt = o["completedAt"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::parseDate),
    )

    private fun parseDate(o: JsonObject): AniListDate? {
        val y = o.int("year")
        val m = o.int("month")
        val d = o.int("day")
        return if (y == null && m == null && d == null) null else AniListDate(y, m, d)
    }

    private fun encodeDate(d: AniListDate): JsonObject = buildJsonObject {
        d.year?.let { put("year", it) }
        d.month?.let { put("month", it) }
        d.day?.let { put("day", it) }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun signedOut() = MediaSourceException("Not signed in to AniList", 401)

    companion object {
        const val CALLBACK_PORT = 47822
        const val CALLBACK_URL = "http://127.0.0.1:$CALLBACK_PORT/anilist-callback"
        const val REDIRECT_URI = "http://localhost:$CALLBACK_PORT/anilist-callback"
        private const val DEFAULT_EXPIRES_SECONDS = 31_536_000L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private const val ENTRY_FIELDS = "id status score progress repeat startedAt { year month day } completedAt { year month day }"
        private const val MEDIA_FIELDS = """
            id idMal format episodes status seasonYear averageScore
            title { romaji english native userPreferred }
            synonyms
            coverImage { medium }
            startDate { year }
            mediaListEntry { $ENTRY_FIELDS }
        """

        private const val VIEWER_QUERY = """
            query { Viewer { id name avatar { medium } mediaListOptions { scoreFormat } } }
        """

        private const val SEARCH_QUERY = """
            query (${"$"}search: String, ${"$"}perPage: Int) {
              Page(page: 1, perPage: ${"$"}perPage) {
                media(search: ${"$"}search, type: ANIME) {
                  $MEDIA_FIELDS
                }
              }
            }
        """

        private const val DETAIL_QUERY = """
            query (${"$"}id: Int) {
              Media(id: ${"$"}id, type: ANIME) {
                $MEDIA_FIELDS
                relations {
                  edges {
                    relationType(version: 2)
                    node {
                      id idMal type format episodes status seasonYear
                      title { romaji english native userPreferred }
                      synonyms
                      coverImage { medium }
                      startDate { year }
                    }
                  }
                }
              }
            }
        """

        private const val MY_LIST_QUERY = """
            query (${"$"}userId: Int, ${"$"}status: MediaListStatus) {
              MediaListCollection(userId: ${"$"}userId, type: ANIME, status: ${"$"}status, sort: UPDATED_TIME_DESC) {
                lists {
                  entries {
                    $ENTRY_FIELDS
                    media {
                      id idMal format episodes status seasonYear averageScore
                      title { romaji english native userPreferred }
                      synonyms
                      coverImage { medium }
                      startDate { year }
                    }
                  }
                }
              }
            }
        """

        private const val SAVE_ENTRY_MUTATION = """
            mutation (${"$"}mediaId: Int, ${"$"}status: MediaListStatus, ${"$"}progress: Int, ${"$"}score: Float, ${"$"}repeat: Int, ${"$"}startedAt: FuzzyDateInput, ${"$"}completedAt: FuzzyDateInput) {
              SaveMediaListEntry(mediaId: ${"$"}mediaId, status: ${"$"}status, progress: ${"$"}progress, score: ${"$"}score, repeat: ${"$"}repeat, startedAt: ${"$"}startedAt, completedAt: ${"$"}completedAt) {
                $ENTRY_FIELDS
              }
            }
        """

        private const val DELETE_ENTRY_MUTATION = """
            mutation (${"$"}id: Int) { DeleteMediaListEntry(id: ${"$"}id) { deleted } }
        """
    }
}
