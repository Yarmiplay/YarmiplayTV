package com.yarmiplaytv.media.mal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A signed-in MyAnimeList account's OAuth tokens. [expiresAtMillis] is when [accessToken] stops working. */
@Serializable
data class MalTokens(val accessToken: String, val refreshToken: String, val expiresAtMillis: Long) {
    override fun toString() = "MalTokens(expiresAtMillis=$expiresAtMillis, tokens=…)"
}

@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
)

@Serializable
data class MalUser(val id: Long = 0, val name: String, val picture: String? = null)

@Serializable
data class MalPicture(val medium: String? = null, val large: String? = null)

@Serializable
data class MalAlternativeTitles(val synonyms: List<String> = emptyList(), val en: String? = null, val ja: String? = null)

/** Your list entry for an anime, as MyAnimeList returns it. */
@Serializable
data class MalListStatus(
    val status: String? = null,
    val score: Int = 0,
    @SerialName("num_episodes_watched") val watchedEpisodes: Int = 0,
    @SerialName("is_rewatching") val isRewatching: Boolean = false,
    @SerialName("num_times_rewatched") val timesRewatched: Int = 0,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("finish_date") val finishDate: String? = null,
) {
    val statusValue: MalStatus? get() = MalStatus.entries.firstOrNull { it.api == status }
}

@Serializable
data class MalRelated(val node: MalAnime, @SerialName("relation_type") val relationType: String = "")

/** An anime entry. MyAnimeList content: shown as it comes, never edited. */
@Serializable
data class MalAnime(
    val id: Int,
    val title: String,
    @SerialName("main_picture") val mainPicture: MalPicture? = null,
    @SerialName("alternative_titles") val alternativeTitles: MalAlternativeTitles? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    /** 0 while unknown (often while airing). */
    @SerialName("num_episodes") val numEpisodes: Int = 0,
    val status: String? = null,
    val mean: Double? = null,
    @SerialName("related_anime") val relatedAnime: List<MalRelated> = emptyList(),
    @SerialName("my_list_status") val myListStatus: MalListStatus? = null,
) {
    val year: Int? get() = startDate?.take(4)?.toIntOrNull()
    val url: String get() = "https://myanimelist.net/anime/$id"
    /** A series entry (TV or web), as opposed to a movie, OVA, special or music video. */
    val isSeries: Boolean get() = mediaType == "tv" || mediaType == "ona"
    val titles: List<String> get() = listOfNotNull(title, alternativeTitles?.en, alternativeTitles?.ja) + alternativeTitles?.synonyms.orEmpty()
}

@Serializable
internal data class MalPage(val data: List<MalNode> = emptyList())

@Serializable
internal data class MalNode(val node: MalAnime, @SerialName("list_status") val listStatus: MalListStatus? = null)

enum class MalStatus(val api: String, val label: String) {
    WATCHING("watching", "Watching"),
    COMPLETED("completed", "Completed"),
    ON_HOLD("on_hold", "On hold"),
    DROPPED("dropped", "Dropped"),
    PLAN_TO_WATCH("plan_to_watch", "Plan to watch"),
}

/** A change to your list entry; null fields stay as they are. Dates are `YYYY-MM-DD`. */
@Serializable
data class MalListUpdate(
    val status: MalStatus? = null,
    val score: Int? = null,
    val watchedEpisodes: Int? = null,
    val isRewatching: Boolean? = null,
    val timesRewatched: Int? = null,
    val startDate: String? = null,
    val finishDate: String? = null,
) {
    /** The form fields of `PATCH /anime/{id}/my_list_status`. */
    fun formFields(): List<Pair<String, String>> = listOfNotNull(
        status?.let { "status" to it.api },
        score?.let { "score" to it.coerceIn(0, 10).toString() },
        watchedEpisodes?.let { "num_watched_episodes" to it.coerceAtLeast(0).toString() },
        isRewatching?.let { "is_rewatching" to it.toString() },
        timesRewatched?.let { "num_times_rewatched" to it.coerceAtLeast(0).toString() },
        startDate?.let { "start_date" to it },
        finishDate?.let { "finish_date" to it },
    )

    companion object {
        /** The update that puts [status] back (for Undo); null when there was no entry. */
        fun restoring(status: MalListStatus): MalListUpdate = MalListUpdate(
            status = status.statusValue,
            score = status.score,
            watchedEpisodes = status.watchedEpisodes,
            isRewatching = status.isRewatching,
            timesRewatched = status.timesRewatched,
            startDate = status.startDate,
            finishDate = status.finishDate,
        )
    }
}

/** A MyAnimeList sign-in, kept on this device only: no password, just the OAuth tokens. */
@Serializable
data class MalAccount(val tokens: MalTokens, val userName: String, val picture: String? = null)

/** A list update for anime [animeId] waiting to be sent. */
@Serializable
data class MalQueued(val animeId: Int, val update: MalListUpdate)
