package com.yarmiplaytv.sync

import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.syncplay.Filenames
import com.yarmiplaytv.syncplay.SyncplayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Edits of a shared playlist, made the way desktop Syncplay makes them. */
object PlaylistEdits {
    /** The [files] that aren't in [playlist] yet (compared like Syncplay compares filenames), in order and without repeats. */
    fun newEntries(playlist: List<String>, files: List<String>): List<String> {
        val added = mutableListOf<String>()
        for (file in files.map { it.trim() }.filter { it.isNotEmpty() }) {
            if (playlist.none { Filenames.same(it, file) } && added.none { Filenames.same(it, file) }) added += file
        }
        return added
    }

    fun remove(playlist: List<String>, indices: Set<Int>): List<String> = playlist.filterIndexed { i, _ -> i !in indices }

    /**
     * Moves the [selected] entries one place up ([delta] -1) or down (+1), keeping their order. Returns the new
     * playlist and the entries' new indices; nothing moves when one of them is already at that end.
     */
    fun shift(playlist: List<String>, selected: Set<Int>, delta: Int): Pair<List<String>, Set<Int>> {
        val sorted = selected.filter { it in playlist.indices }.sorted()
        if (sorted.isEmpty() || (delta < 0 && sorted.first() == 0) || (delta > 0 && sorted.last() == playlist.lastIndex)) {
            return playlist to selected
        }
        val list = playlist.toMutableList()
        for (i in if (delta < 0) sorted else sorted.reversed()) list.add(i + delta, list.removeAt(i))
        return list to sorted.map { it + delta }.toSet()
    }

    fun move(playlist: List<String>, from: Int, to: Int): List<String> {
        if (from !in playlist.indices || to !in playlist.indices || from == to) return playlist
        return playlist.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** "Shuffle remaining" keeps everything up to the current entry in place; [entire] shuffles all of it. */
    fun shuffle(playlist: List<String>, index: Int?, entire: Boolean, random: Random = Random.Default): List<String> {
        if (entire || index == null) return playlist.shuffled(random)
        val pivot = index + 1
        return playlist.take(pivot) + playlist.drop(pivot).shuffled(random)
    }

    /** Syncplay's playlist files: one entry per line. */
    fun parse(text: String): List<String> = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    fun format(playlist: List<String>): String = playlist.joinToString("\n", postfix = "\n")
}

/** Syncplay's trusted domains: URLs the room may switch everyone to without asking. */
object TrustedDomains {
    val DEFAULT = listOf("youtube.com", "youtu.be")
    private val PROTOCOLS = listOf("http://www.", "https://www.", "http://", "https://")

    /** Port of Syncplay's isURITrusted: an entry is a domain, optionally followed by a path. */
    fun isTrusted(url: String, domains: List<String>): Boolean {
        val candidate = "$url/"
        return PROTOCOLS.any { protocol ->
            domains.any { domain -> domain.isNotBlank() && candidate.startsWith("$protocol${domain.trim().trimEnd('/')}/", ignoreCase = true) }
        }
    }

    /** The host of [url] without "www.", the entry "Trust this domain" adds. */
    fun domainOf(url: String): String? =
        runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }
}

/**
 * Edits the room's shared playlist like desktop Syncplay: every edit sends the whole new list. Edits by anyone
 * in the room can be undone, newest first; the history starts over in each room.
 */
class SharedPlaylist(scope: CoroutineScope, private val sync: SyncController) {
    private val history = ArrayDeque<List<String>>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    /** The list we last sent, until the room state shows it; quick successive edits build on it. */
    private var sent: List<String>? = null
    private val playlist: List<String> get() = sent ?: sync.room.value.playlist

    init {
        scope.launch {
            sync.room.map { (it.status == ConnectionStatus.CONNECTED) to it.room }.distinctUntilChanged().collect { clearHistory() }
        }
        scope.launch {
            sync.room.map { it.playlist }.distinctUntilChanged().collect { if (it == sent) sent = null }
        }
        scope.launch {
            sync.events.collect { event ->
                // The list sent on joining has no user; our own edits are recorded when we make them.
                if (event is SyncplayEvent.PlaylistChanged && event.setBy != null && event.setBy != sync.room.value.username) {
                    sent = null
                    record(event.previous)
                }
            }
        }
    }

    /** Adds the entries that aren't in the playlist yet; returns how many were added. */
    fun add(files: List<String>): Int {
        val added = PlaylistEdits.newEntries(playlist, files)
        if (added.isNotEmpty()) apply(playlist + added)
        return added.size
    }

    fun remove(indices: Set<Int>) = apply(PlaylistEdits.remove(playlist, indices))

    /** Moves the selected entries up or down one place; returns their new indices. */
    fun shift(selected: Set<Int>, delta: Int): Set<Int> {
        val (moved, newSelection) = PlaylistEdits.shift(playlist, selected, delta)
        apply(moved)
        return newSelection
    }

    fun move(from: Int, to: Int) = apply(PlaylistEdits.move(playlist, from, to))

    /** Like Syncplay, shuffling the entire playlist starts it over from the first entry. */
    fun shuffle(entire: Boolean, random: Random = Random.Default) {
        val index = sync.room.value.playlistIndex
        apply(PlaylistEdits.shuffle(playlist, index, entire, random))
        if (entire || index == null) sync.client?.selectPlaylistIndex(0)
    }

    /** Replaces the playlist, e.g. with one loaded from a file. */
    fun replace(files: List<String>) = apply(files.map { it.trim() }.filter { it.isNotEmpty() }.distinct())

    fun undo() {
        val client = sync.client ?: return
        val previous = history.removeLastOrNull() ?: return
        _canUndo.value = history.isNotEmpty()
        sent = previous
        client.setPlaylist(previous)
    }

    private fun apply(files: List<String>) {
        val current = playlist
        val client = sync.client ?: return
        if (files == current) return
        record(current)
        sent = files
        client.setPlaylist(files)
    }

    private fun record(previous: List<String>) {
        history.addLast(previous)
        while (history.size > MAX_UNDO) history.removeFirst()
        _canUndo.value = true
    }

    private fun clearHistory() {
        history.clear()
        sent = null
        _canUndo.value = false
    }

    private companion object {
        const val MAX_UNDO = 50
    }
}
