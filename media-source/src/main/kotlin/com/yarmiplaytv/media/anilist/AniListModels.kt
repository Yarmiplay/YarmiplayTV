package com.yarmiplaytv.media.anilist

import kotlinx.serialization.Serializable

/**
 * AniList OAuth 2 Implicit Grant token (valid 1 year; no client secret needed in public clients).
 * Never logged or included in [toString].
 */
@Serializable
data class AniListTokens(
    val accessToken: String,
    val expiresAtMillis: Long,
) {
    override fun toString() = "AniListTokens(expiresAtMillis=$expiresAtMillis, token=…)"
}

/** AniList user score display format (`Viewer.mediaListOptions.scoreFormat`). */
enum class AniListScoreFormat(val api: String, val maxScore: Double, val step: Double) {
    POINT_100("POINT_100", 100.0, 5.0),
    POINT_10_DECIMAL("POINT_10_DECIMAL", 10.0, 0.5),
    POINT_10("POINT_10", 10.0, 1.0),
    POINT_5("POINT_5", 5.0, 1.0),
    POINT_3("POINT_3", 3.0, 1.0);

    fun format(score: Double): String = when {
        score <= 0.0 -> "No score"
        this == POINT_10_DECIMAL -> "%.1f / 10".format(score)
        this == POINT_3 -> when (score.toInt()) {
            1 -> ":( (1/3)"
            2 -> ":| (2/3)"
            else -> ":) (3/3)"
        }
        else -> "${score.toInt()} / ${maxScore.toInt()}"
    }

    companion object {
        fun parse(raw: String?): AniListScoreFormat =
            entries.firstOrNull { it.api.equals(raw, ignoreCase = true) } ?: POINT_10
    }
}

/** Signed-in AniList account stored locally on this device only. */
@Serializable
data class AniListAccount(
    val tokens: AniListTokens,
    val userId: Int,
    val userName: String,
    val avatarUrl: String? = null,
    val scoreFormat: String = AniListScoreFormat.POINT_10.api,
) {
    val scoreFormatEnum: AniListScoreFormat get() = AniListScoreFormat.parse(scoreFormat)
}

enum class AniListStatus(val api: String, val label: String) {
    CURRENT("CURRENT", "Watching"),
    COMPLETED("COMPLETED", "Completed"),
    PAUSED("PAUSED", "Paused"),
    DROPPED("DROPPED", "Dropped"),
    PLANNING("PLANNING", "Plan to watch"),
    REPEATING("REPEATING", "Rewatching");

    companion object {
        fun parse(raw: String?): AniListStatus? = entries.firstOrNull { it.api.equals(raw, ignoreCase = true) }
    }
}

@Serializable
data class AniListDate(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
) {
    companion object {
        fun fromIso(iso: String): AniListDate? {
            val parts = iso.split('-')
            if (parts.size != 3) return null
            val y = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            val d = parts[2].toIntOrNull() ?: return null
            return AniListDate(y, m, d)
        }
    }
}

@Serializable
data class AniListEntryStatus(
    val id: Int = 0,
    val status: String? = null,
    val score: Double = 0.0,
    val progress: Int = 0,
    val repeat: Int = 0,
    val startedAt: AniListDate? = null,
    val completedAt: AniListDate? = null,
) {
    val statusValue: AniListStatus? get() = AniListStatus.parse(status)
}

@Serializable
data class AniListRelation(
    val relationType: String = "",
    val media: AniListAnime,
)

@Serializable
data class AniListAnime(
    val id: Int,
    val idMal: Int? = null,
    val title: String,
    val englishTitle: String? = null,
    val nativeTitle: String? = null,
    val synonyms: List<String> = emptyList(),
    val coverUrl: String? = null,
    val seasonYear: Int? = null,
    val format: String? = null,
    /** 0 when unknown (often while airing). */
    val episodes: Int = 0,
    val status: String? = null,
    val averageScore: Int? = null,
    val relations: List<AniListRelation> = emptyList(),
    val listEntry: AniListEntryStatus? = null,
) {
    val url: String get() = "https://anilist.co/anime/$id"
    val isSeries: Boolean get() = format == "TV" || format == "TV_SHORT" || format == "ONA"
    val titles: List<String> get() = listOfNotNull(title, englishTitle, nativeTitle) + synonyms
}

@Serializable
data class AniListUpdate(
    val status: AniListStatus? = null,
    val score: Double? = null,
    val progress: Int? = null,
    val repeat: Int? = null,
    val startedAt: AniListDate? = null,
    val completedAt: AniListDate? = null,
) {
    companion object {
        fun restoring(entry: AniListEntryStatus): AniListUpdate = AniListUpdate(
            status = entry.statusValue,
            score = entry.score,
            progress = entry.progress,
            repeat = entry.repeat,
            startedAt = entry.startedAt,
            completedAt = entry.completedAt,
        )
    }
}

@Serializable
data class AniListQueued(
    val mediaId: Int,
    val update: AniListUpdate,
)

data class AniListMatch(
    val anime: AniListAnime,
    val episode: Int,
    val sure: Boolean,
    val candidates: List<AniListAnime> = emptyList(),
)
