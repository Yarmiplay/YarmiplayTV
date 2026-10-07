package com.yarmiplaytv.sync

import com.yarmiplaytv.Logger
import com.yarmiplaytv.media.jellyfin.JellyfinClient
import com.yarmiplaytv.media.jellyfin.JellyfinSession
import com.yarmiplaytv.media.jellyfin.QuickConnectState
import com.yarmiplaytv.syncplay.SharedJellyfin
import com.yarmiplaytv.syncplay.SyncplayEvent
import com.yarmiplaytv.syncplay.YarmiplaySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Adds the Jellyfin a YarmiplayServerTV host shares: finds an address that answers as that server, signs in with
 * Quick Connect (the Syncplay server approves the code for its guest account) and saves it as shared by the host.
 */
class HostJellyfinSharing(
    private val scope: CoroutineScope,
    private val sync: SyncController,
    private val jellyfin: JellyfinClient,
    /** The saved Jellyfin servers. */
    private val saved: () -> List<JellyfinSession>,
    private val enabled: () -> Boolean,
    private val save: (JellyfinSession) -> Unit,
) {
    private data class Offer(val shared: SharedJellyfin, val session: YarmiplaySession?, val on: Boolean)

    /** Server ids already handled on the current session, so a re-sent `jellyfin` message doesn't ask again. */
    private val handled = HashSet<String>()
    private var handledFor: YarmiplaySession? = null

    fun start() {
        scope.launch {
            combine(sync.room, sync.session) { room, session ->
                Offer(room.yarmiplay.jellyfin, session, room.yarmiplay.capabilities.jellyfin)
            }.distinctUntilChanged().collectLatest { offer ->
                val session = offer.session ?: return@collectLatest
                if (handledFor !== session) {
                    handled.clear()
                    handledFor = session
                }
                val shared = offer.shared
                if (!offer.on || !shared.available || shared.serverId.isEmpty() || !enabled()) return@collectLatest
                if (!handled.add(shared.serverId)) return@collectLatest
                runCatching { add(shared, session) }.onFailure {
                    handled.remove(shared.serverId)
                    Logger.w(TAG, "Couldn't add the host's Jellyfin: ${it.message}")
                }
            }
        }
    }

    private suspend fun add(shared: SharedJellyfin, session: YarmiplaySession) {
        val candidates = candidates(shared, session)
        val existing = saved().firstOrNull { it.serverId == shared.serverId }
        if (existing != null && !existing.noLongerShared) {
            val valid = runCatching { jellyfin.validate(existing) }.getOrNull()
            if (valid == true) {
                if (existing.isShared && existing.candidateUrls != candidates) save(existing.copy(candidateUrls = candidates))
                return
            }
            if (valid == null && existing.isShared) {
                // Its address doesn't answer from here; another one of the host's may.
                val url = probe(candidates, shared.serverId) ?: return
                val moved = existing.copy(serverUrl = url, candidateUrls = candidates)
                if (runCatching { jellyfin.validate(moved) }.getOrDefault(false)) save(moved)
                return
            }
            // A server the user signed in to themselves stays theirs, even with an expired sign-in.
            if (!existing.isShared) return
        }
        val url = probe(candidates, shared.serverId) ?: run {
            Logger.w(TAG, "None of the host's Jellyfin addresses answered as server ${shared.serverId}")
            return
        }
        if (!jellyfin.quickConnectEnabled(url)) {
            Logger.w(TAG, "The host's Jellyfin has Quick Connect switched off")
            return
        }
        val via = sync.server?.substringBefore(':') ?: "Syncplay"
        jellyfin.quickConnect(url).takeWhile { state ->
            when (state) {
                is QuickConnectState.WaitingForApproval -> authorize(state.code)
                is QuickConnectState.Authorized -> {
                    val name = shared.serverName.ifBlank { state.session.serverName }
                    save(state.session.copy(serverName = "$name (via $via)", sharedBy = via, candidateUrls = candidates))
                    sync.postLocal("Added $name, shared by the Syncplay host")
                    false
                }
            }
        }.collect {}
    }

    /** Sends the Quick Connect code to the Syncplay server; true when it approved it. */
    private suspend fun authorize(code: String): Boolean = kotlinx.coroutines.coroutineScope {
        val answer = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(AUTHORIZE_TIMEOUT_MS) {
                sync.events.filterIsInstance<SyncplayEvent.JellyfinAuthorized>().first { it.code == code }
            }
        }
        sync.authorizeJellyfin(code)
        val result = answer.await()
        when {
            result == null -> Logger.w(TAG, "The Syncplay server didn't answer the Jellyfin authorization")
            !result.ok -> sync.postLocal("Couldn't add the host's Jellyfin: ${result.error ?: "the server refused"}", isError = true)
        }
        result?.ok == true
    }

    /** The first of [candidates] that answers as Jellyfin server [serverId]. */
    private suspend fun probe(candidates: List<String>, serverId: String): String? {
        for (url in candidates) {
            val base = runCatching { jellyfin.normalizeServerUrl(url) }.getOrNull() ?: continue
            val info = runCatching { jellyfin.publicInfo(base) }.getOrNull() ?: continue
            if (info.id == serverId) return base
        }
        return null
    }

    companion object {
        private const val TAG = "HostJellyfinSharing"
        private const val AUTHORIZE_TIMEOUT_MS = 30_000L

        /** The Syncplay server's own address first when it proxies Jellyfin, then the host's addresses. */
        fun candidates(shared: SharedJellyfin, session: YarmiplaySession): List<String> =
            (listOfNotNull(session.baseUrl.takeIf { shared.proxy }) + shared.addresses).distinct()
    }
}
