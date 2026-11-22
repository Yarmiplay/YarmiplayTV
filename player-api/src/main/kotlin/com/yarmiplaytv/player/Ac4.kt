package com.yarmiplaytv.player

/**
 * AC-4 on Android, decided without touching the player libraries.
 *
 * Stock mpv has no `audio-spdif` name for AC-4 and this build does not decode it. Whether a device can
 * play it is answered by the platform (direct playback to HDMI, or a platform `audio/ac4` decoder).
 * The result is this plan: which track the list shows, whether mpv is told to play a different track
 * or none, and whether Android should play the AC-4 access units itself.
 */
enum class Ac4Route {
    /** The device accepts an AC-4 bitstream on an [android.media.AudioTrack]. */
    PASSTHROUGH,

    /** The platform has an `audio/ac4` decoder, so the frames can be turned into PCM. */
    DECODE,

    /** Nothing on the device can play AC-4. */
    NONE,
}

/** What the user or mpv asked for, before the device's capability is applied. */
sealed interface Ac4Selection {
    /** Use the track mpv already selected. */
    data object Follow : Ac4Selection

    /** The user picked [id], or turned audio off when [id] is null. */
    data class Pick(val id: Int?) : Ac4Selection
}

/** One AC-4 stream found by the platform demuxer when mpv did not list it. */
data class Ac4Stream(val language: String?)

/**
 * The audio decision for one track list.
 *
 * [mpvAid] is what mpv should be told to play, and is meaningful only when [overrideMpv] is set.
 * Null means no audio from mpv, which is how an AC-4 track is kept out of a decoder that cannot play it.
 * [ordinal] counts AC-4 tracks in list order and is the one Android opens.
 */
data class Ac4Plan(
    val tracks: List<Track>,
    val selectedId: Int?,
    val mpvAid: Int?,
    val overrideMpv: Boolean,
    val playOnAndroid: Boolean,
    val ordinal: Int,
)

object Ac4 {
    const val MIME = "audio/ac4"

    /** mpv's own track ids are small. Platform-only AC-4 tracks start here so the two cannot collide. */
    const val SYNTHETIC_ID_BASE = 1_000_001

    private val CODEC = Regex("""(^|[^a-z0-9])ac-?4([^a-z0-9]|$)""")

    fun route(directPlayback: Boolean, platformDecoder: Boolean): Ac4Route = when {
        directPlayback -> Ac4Route.PASSTHROUGH
        platformDecoder -> Ac4Route.DECODE
        else -> Ac4Route.NONE
    }

    fun isAc4Codec(codec: String?): Boolean = codec != null && CODEC.containsMatchIn(codec.lowercase())

    /** The id of the AC-4 track at [ordinal], in list order. */
    fun trackId(tracks: List<Track>, ordinal: Int): Int? =
        tracks.filter { it.type == TrackType.AUDIO && isAc4Codec(it.codec) }.getOrNull(ordinal)?.id

    /**
     * Adds an AC-4 row for each platform stream mpv did not already announce.
     * When mpv listed the codec, its row is the one the user selects.
     */
    fun withStreams(tracks: List<Track>, streams: List<Ac4Stream>): List<Track> {
        if (streams.isEmpty() || tracks.any { it.type == TrackType.AUDIO && isAc4Codec(it.codec) }) return tracks
        val added = streams.mapIndexed { index, stream ->
            Track(
                id = SYNTHETIC_ID_BASE + index,
                type = TrackType.AUDIO,
                title = null,
                language = stream.language,
                codec = "ac4",
                selected = false,
                isDefault = false,
                isExternal = false,
            )
        }
        return tracks + added
    }

    fun plan(tracks: List<Track>, selection: Ac4Selection, route: Ac4Route): Ac4Plan {
        if (selection is Ac4Selection.Pick && selection.id == null) return off(tracks, route)
        val audio = tracks.filter { it.type == TrackType.AUDIO }
        val wanted = when (selection) {
            is Ac4Selection.Pick -> audio.find { it.id == selection.id } ?: audio.find { it.selected }
            Ac4Selection.Follow -> audio.find { it.selected }
                ?: audio.takeIf { rows -> rows.isNotEmpty() && rows.all { isAc4Codec(it.codec) } }?.firstOrNull()
                ?: audio.firstOrNull { route == Ac4Route.NONE && !isAc4Codec(it.codec) && it.isDefault }
                ?: audio.firstOrNull { route == Ac4Route.NONE && !isAc4Codec(it.codec) }
        }
        val ac4 = wanted != null && isAc4Codec(wanted.codec)
        val playOnAndroid = ac4 && route != Ac4Route.NONE
        val fallback = if (ac4 && !playOnAndroid) {
            audio.firstOrNull { it.id != wanted.id && !isAc4Codec(it.codec) && it.isDefault }
                ?: audio.firstOrNull { it.id != wanted.id && !isAc4Codec(it.codec) }
        } else {
            null
        }
        val chosen = when {
            playOnAndroid -> wanted
            fallback != null -> fallback
            ac4 -> null
            else -> wanted
        }
        val ordinal = if (playOnAndroid) audio.filter { isAc4Codec(it.codec) }.indexOfFirst { it.id == chosen?.id } else -1
        val override = playOnAndroid || (ac4 && chosen?.id != wanted?.id) || selection is Ac4Selection.Pick ||
            (chosen != null && audio.none { it.selected })
        return Ac4Plan(
            tracks = render(tracks, chosen?.id, route),
            selectedId = chosen?.id,
            mpvAid = if (playOnAndroid) null else chosen?.id,
            overrideMpv = override,
            playOnAndroid = playOnAndroid,
            ordinal = ordinal,
        )
    }

    private fun off(tracks: List<Track>, route: Ac4Route) = Ac4Plan(
        tracks = render(tracks, selectedId = null, route),
        selectedId = null,
        mpvAid = null,
        overrideMpv = true,
        playOnAndroid = false,
        ordinal = -1,
    )

    private fun render(tracks: List<Track>, selectedId: Int?, route: Ac4Route): List<Track> = tracks.map { track ->
        if (track.type != TrackType.AUDIO) track
        else track.copy(selected = track.id == selectedId, playable = !isAc4Codec(track.codec) || route != Ac4Route.NONE)
    }
}
