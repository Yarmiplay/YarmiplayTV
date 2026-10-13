package com.yarmiplaytv.media.aniskip

import com.yarmiplaytv.media.MediaSourceException
import com.yarmiplaytv.media.SkipSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Where an anime episode's opening and ending are, from AniSkip (api.aniskip.com: open source, timestamps sent in by
 * its users, no account or key). Asked with the MyAnimeList id, the episode number and the file's length, nothing
 * else.
 */
class AniSkipClient(
    http: OkHttpClient? = null,
    private val apiUrl: String = "https://api.aniskip.com/v2",
) {
    private val http: OkHttpClient = http ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    /**
     * The opening and ending of episode [episode] of MyAnimeList anime [malId], for a file [lengthSeconds] long;
     * empty when AniSkip knows none. AniSkip picks the times measured on the closest-length file; times measured
     * on a file of another length (a different cut) are left out, as are any outside the file.
     */
    suspend fun skipTimes(malId: Int, episode: Int, lengthSeconds: Double): List<SkipSegment> = withContext(Dispatchers.IO) {
        val url = "$apiUrl/skip-times/$malId/$episode".toHttpUrl().newBuilder()
            .addQueryParameter("types[]", "op")
            .addQueryParameter("types[]", "ed")
            .addQueryParameter("episodeLength", String.format(Locale.ROOT, "%.3f", lengthSeconds))
            .build()
        val text = try {
            http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
                if (response.code == 404) return@withContext emptyList()
                if (!response.isSuccessful) throw MediaSourceException("AniSkip answered ${response.code}", response.code)
                response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw MediaSourceException("Cannot reach AniSkip (${e.message})", cause = e)
        }
        val answer = runCatching { json.decodeFromString(SkipTimes.serializer(), text) }
            .getOrElse { throw MediaSourceException("AniSkip sent something unreadable") }
        if (!answer.found) return@withContext emptyList()
        answer.results.mapNotNull { it.segment(lengthSeconds) }.sortedBy { it.start }
    }

    companion object {
        /** Times measured on a file more than this many seconds longer or shorter don't fit this one. */
        const val LENGTH_TOLERANCE = 5.0
    }
}

@Serializable
internal data class SkipTimes(val found: Boolean = false, val results: List<SkipResult> = emptyList())

@Serializable
internal data class SkipResult(
    val interval: SkipInterval,
    @SerialName("skipType") val type: String,
    @SerialName("episodeLength") val episodeLength: Double = 0.0,
) {
    fun segment(length: Double): SkipSegment? {
        val kind = when (type) {
            "op" -> SkipSegment.Kind.OPENING
            "ed" -> SkipSegment.Kind.ENDING
            else -> return null
        }
        if (length > 0 && episodeLength > 0 && abs(episodeLength - length) > AniSkipClient.LENGTH_TOLERANCE) return null
        val start = interval.startTime.coerceAtLeast(0.0)
        val end = if (length > 0) interval.endTime.coerceAtMost(length) else interval.endTime
        if (end - start < 1.0 || (length > 0 && start >= length)) return null
        return SkipSegment(kind, start, end)
    }
}

@Serializable
internal data class SkipInterval(val startTime: Double, val endTime: Double)
