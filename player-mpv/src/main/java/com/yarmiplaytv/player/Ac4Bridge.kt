package com.yarmiplaytv.player

import android.content.Context
import android.util.Log
import kotlin.concurrent.thread

/**
 * Keeps mpv off an AC-4 track and, when the device can play it, runs an [Ac4Session] beside the picture.
 * mpv stays the clock. A device that cannot play AC-4 keeps the current audio track, and the AC-4 row
 * stays in the list marked unsupported.
 */
internal class Ac4Bridge(
    private val context: Context,
    private val clock: () -> Double,
    private val transport: () -> Ac4Transport,
    private val post: (() -> Unit) -> Unit,
    private val publish: (List<Track>) -> Unit,
    private val setAid: (Int?) -> Unit,
) {
    private val capability by lazy { Ac4Devices.capability(context) }
    private var generation = 0
    private var url: String? = null
    private var mpvTracks: List<Track> = emptyList()
    private var streams: List<Ac4Stream> = emptyList()
    private var explicit = false
    private var wantedId: Int? = null
    private var lockedOrdinal: Int? = null
    private var failed = false
    private var sessionKey: String? = null
    private var session: Ac4Session? = null

    fun reset() {
        generation++
        url = null
        mpvTracks = emptyList()
        streams = emptyList()
        explicit = false
        wantedId = null
        lockedOrdinal = null
        failed = false
        stopSession()
    }

    fun onMpvTracks(tracks: List<Track>, playingUrl: String?) {
        if (playingUrl != url) {
            generation++
            url = playingUrl
            streams = emptyList()
            explicit = false
            wantedId = null
            lockedOrdinal = null
            failed = false
            stopSession()
            if (playingUrl != null && tracks.none { it.type == TrackType.AUDIO && Ac4.isAc4Codec(it.codec) }) probe(playingUrl, generation)
        }
        mpvTracks = tracks
        deliver()
    }

    /** The user picked an audio track, or turned audio off. */
    fun onAudioSelected(id: Int?) {
        explicit = true
        wantedId = id
        if (id == null || Ac4.trackId(merged(), lockedOrdinal ?: -1) != id) lockedOrdinal = null
        deliver()
    }

    fun seekTo(seconds: Double) {
        session?.seekTo(seconds)
    }

    fun onPlaybackFailed(generation: Int) {
        if (generation != this.generation) return
        failed = true
        lockedOrdinal = null
        explicit = false
        stopSession()
        deliver()
    }

    private fun deliver() {
        val merged = merged()
        val selection = selection(merged)
        val route = if (failed) Ac4Route.NONE else capability.route
        val plan = Ac4.plan(merged, selection, route)
        publish(plan.tracks)
        if (plan.overrideMpv) setAid(plan.mpvAid)
        val playing = url
        if (plan.playOnAndroid && playing != null && plan.ordinal >= 0) {
            lockedOrdinal = plan.ordinal
            start(playing, plan.ordinal)
        } else {
            if (!plan.playOnAndroid) lockedOrdinal = null
            stopSession()
        }
    }

    private fun selection(tracks: List<Track>): Ac4Selection {
        if (explicit) return Ac4Selection.Pick(wantedId)
        val locked = lockedOrdinal?.let { Ac4.trackId(tracks, it) }
        return if (locked != null) Ac4Selection.Pick(locked) else Ac4Selection.Follow
    }

    private fun merged(): List<Track> = Ac4.withStreams(mpvTracks, streams)

    private fun probe(playingUrl: String, generation: Int) {
        thread(name = "ac4-probe", isDaemon = true) {
            val found = runCatching { Ac4Source.languages(context, playingUrl).map { Ac4Stream(it) } }.getOrDefault(emptyList())
            post {
                if (generation != this.generation || playingUrl != url || found.isEmpty()) return@post
                streams = found
                deliver()
            }
        }
    }

    private fun start(playingUrl: String, ordinal: Int) {
        val key = "$generation|$ordinal|${capability.route}"
        if (key == sessionKey && session != null) return
        stopSession()
        val generation = generation
        sessionKey = key
        val opened = Ac4Session(context, playingUrl, ordinal, capability, clock, transport) { error ->
            Log.w(TAG, MediaUrls.redact("AC-4 fell back after ${error.javaClass.simpleName}"))
            post { onPlaybackFailed(generation) }
        }
        session = opened
        opened.open()
    }

    private fun stopSession() {
        sessionKey = null
        val running = session
        session = null
        running?.close()
    }

    private companion object {
        private const val TAG = "Ac4"
    }
}
