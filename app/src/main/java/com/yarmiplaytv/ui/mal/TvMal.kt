package com.yarmiplaytv.ui.mal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.media.EpisodeInfo
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.mal.MalAnime
import com.yarmiplaytv.media.mal.MalListUpdate
import com.yarmiplaytv.media.mal.MalStatus
import com.yarmiplaytv.tracking.MalService
import com.yarmiplaytv.tracking.MalState
import com.yarmiplaytv.ui.components.ActionButton
import com.yarmiplaytv.ui.components.SectionTitle
import com.yarmiplaytv.ui.components.ToggleRow
import com.yarmiplaytv.ui.components.TvTextField
import com.yarmiplaytv.ui.components.TvTile
import com.yarmiplaytv.ui.components.ValueRow
import com.yarmiplaytv.ui.mobile.MalSignInPage
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.shared.MAL_ATTRIBUTION
import com.yarmiplaytv.ui.shared.MAL_PRIVACY_NOTE
import com.yarmiplaytv.ui.shared.PRIVACY_POLICY_URL
import com.yarmiplaytv.ui.shared.malInfo
import com.yarmiplaytv.ui.shared.rememberMalLoginModel
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * Signing in to MyAnimeList on TV, which usually has no browser: what's shared and kept first, then MyAnimeList's
 * own page in a web view. The app only watches where the page goes (to catch the redirect); it never reads the
 * form, and the web view's cookies and storage are deleted afterwards.
 */
@Composable
fun MalLoginScreen(container: AppContainer, nav: Navigator) {
    val model = rememberMalLoginModel(container)
    var url by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val page = url
    if (page != null && model.status == null && model.error == null) {
        MalSignInPage(page, { target -> model.onRedirect(target) { nav.back() } }, Modifier.fillMaxSize())
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("MyAnimeList", style = MaterialTheme.typography.headlineMedium)
        if (!model.configured) {
            Text("MyAnimeList isn't set up in this build of YarmiplayTV.", color = AppColors.TextDim)
            return@Column
        }
        Text(MAL_PRIVACY_NOTE, modifier = Modifier.width(900.dp))
        Text("Privacy policy: $PRIVACY_POLICY_URL", color = AppColors.TextDim)
        ActionButton("Continue to MyAnimeList", { url = model.begin() }, Modifier.focusRequester(first).testTag("mal_sign_in"), primary = true, enabled = !model.busy)
        model.status?.let { Text(it, color = AppColors.TextDim) }
        model.error?.let { Text(it, color = AppColors.Error) }
        Text(MAL_ATTRIBUTION, color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall)
    }
}

/** A show from the library on MyAnimeList (TV). */
@Composable
fun MalEntryScreen(container: AppContainer, nav: Navigator, item: MediaItem) {
    val info = remember(item) { item.malInfo() }
    var state by remember { mutableStateOf<MalState?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    fun reload() {
        if (info != null) scope.launch { state = container.mal.lookup(info) }
    }
    LaunchedEffect(info) { reload() }
    LaunchedEffect(state != null) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 40.dp)) {
        state?.let { TvMalContent(container, it, focus, onChanged = ::reload, onSignIn = { nav.push(com.yarmiplaytv.ui.nav.Screen.MalLogin) }) }
            ?: Text(if (info == null) "This isn't a show." else "Looking it up…", color = AppColors.TextDim)
    }
}

/** The player's MyAnimeList panel (TV). */
@Composable
fun MalPanel(container: AppContainer, focus: FocusRequester) {
    val state by container.mal.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        TvMalContent(container, state, focus)
    }
}

@Composable
private fun TvMalContent(container: AppContainer, state: MalState, focus: FocusRequester, onChanged: () -> Unit = {}, onSignIn: (() -> Unit)? = null) {
    val mal = container.mal
    var picking by remember(state) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("mal_content")) {
        Text("MyAnimeList", style = MaterialTheme.typography.headlineSmall)
        when (state) {
            MalState.NotConfigured -> Text("MyAnimeList isn't set up in this build.", color = AppColors.TextDim)
            MalState.SignedOut -> {
                Text("Sign in to MyAnimeList in Settings to keep your list up to date as you watch.", color = AppColors.TextDim)
                onSignIn?.let { ActionButton("Sign in", it, Modifier.focusRequester(focus)) }
            }
            MalState.Idle -> Text("This isn't an episode of a show.", color = AppColors.TextDim)
            is MalState.Matching -> Text("Finding ${state.info.seriesName}…", color = AppColors.TextDim)
            is MalState.Failed -> {
                Text(state.message, color = AppColors.Error)
                ActionButton("Try again", { mal.refresh(); onChanged() }, Modifier.focusRequester(focus))
            }
            is MalState.Ignored -> {
                Text("${state.info.seriesName} isn't tracked.", color = AppColors.TextDim)
                ActionButton("Track this show", { mal.ignore(state.info.seriesKey, false); onChanged() }, Modifier.focusRequester(focus))
            }
            is MalState.Unsure -> TvPicker(container, state.info, state.candidates, focus, onChanged)
            is MalState.Matched -> if (picking) {
                TvPicker(container, state.info, state.match.candidates.ifEmpty { listOf(state.entry) }, focus, onChanged)
            } else {
                TvEntry(container, state, focus, onChanged) { picking = true }
            }
        }
        Text(MAL_ATTRIBUTION, color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TvEntry(container: AppContainer, state: MalState.Matched, focus: FocusRequester, onChanged: () -> Unit, onWrongEntry: () -> Unit) {
    val mal = container.mal
    val anime = state.entry
    val list = anime.myListStatus
    val canUndo by mal.canUndo.collectAsStateWithLifecycle()
    fun edit(update: MalListUpdate) {
        mal.edit(anime, update)
        onChanged()
    }
    Row(verticalAlignment = Alignment.Top) {
        Cover(anime, Modifier.width(96.dp).height(136.dp))
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, style = MaterialTheme.typography.titleLarge)
            val of = if (anime.numEpisodes > 0) " of ${anime.numEpisodes}" else ""
            Text("This is episode ${state.match.episode}$of", color = AppColors.TextDim)
            Text(
                list?.let { "Your list: ${it.statusValue?.label ?: "—"} · ${it.watchedEpisodes}$of watched" + (if (it.score > 0) " · ${it.score}/10" else "") }
                    ?: "Not on your list yet",
                color = AppColors.TextDim,
            )
        }
    }
    ActionButton("Mark episode ${state.match.episode} watched", { container.scope.launch { mal.record(anime, state.match.episode, automatic = true); onChanged() } },
        Modifier.focusRequester(focus).testTag("mal_mark_watched"), icon = Icons.Filled.CheckCircle, primary = true)
    if (canUndo) ActionButton("Undo the last update", { mal.undo(); onChanged() }, Modifier.testTag("mal_undo"))
    val watched = list?.watchedEpisodes ?: 0
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionButton("One fewer", { edit(MalListUpdate(watchedEpisodes = (watched - 1).coerceAtLeast(0))) }, icon = Icons.Filled.Remove, enabled = watched > 0)
        Text("$watched watched")
        ActionButton("One more", { edit(MalListUpdate(watchedEpisodes = watched + 1)) }, icon = Icons.Filled.Add, enabled = anime.numEpisodes == 0 || watched < anime.numEpisodes)
    }
    // Status and score step through their values: easier with a remote than a menu.
    val status = list?.statusValue
    ActionButton("Status: ${status?.label ?: "Not set"}", {
        val next = MalStatus.entries[((status?.ordinal ?: -1) + 1) % MalStatus.entries.size]
        edit(MalListUpdate(status = next))
    }, Modifier.testTag("mal_status"))
    val score = list?.score ?: 0
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionButton("Score −", { edit(MalListUpdate(score = (score - 1).coerceAtLeast(0))) }, enabled = score > 0)
        Text(if (score > 0) "$score/10" else "Not rated")
        ActionButton("Score +", { edit(MalListUpdate(score = (score + 1).coerceAtMost(10))) }, enabled = score < 10, modifier = Modifier.testTag("mal_score_plus"))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton("Wrong entry?", onWrongEntry, Modifier.testTag("mal_wrong_entry"))
        ActionButton("Don't track this show", { mal.ignore(state.info.seriesKey, true, state.info.seriesName); onChanged() })
    }
}

@Composable
private fun TvPicker(container: AppContainer, info: EpisodeInfo, candidates: List<MalAnime>, focus: FocusRequester, onChanged: () -> Unit) {
    var query by remember(info) { mutableStateOf(info.seriesName) }
    var results by remember(candidates) { mutableStateOf(candidates) }
    var chosen by remember(info) { mutableStateOf<MalAnime?>(null) }
    var episode by remember(info) { mutableStateOf(1) }
    val scope = rememberCoroutineScope()
    val season = info.season?.let { "season $it " }.orEmpty()
    Text("Which MyAnimeList entry is ${info.seriesName} ${season}episode ${info.episode ?: "?"}?")
    val pick = chosen
    if (pick != null) {
        Text(pick.title, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton("Episode −", { episode = (episode - 1).coerceAtLeast(1) }, enabled = episode > 1)
            Text("Its episode $episode" + if (pick.numEpisodes > 0) " of ${pick.numEpisodes}" else "")
            ActionButton("Episode +", { episode += 1 }, enabled = pick.numEpisodes == 0 || episode < pick.numEpisodes)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("That's it", { container.mal.choose(info, pick, episode); onChanged() }, Modifier.focusRequester(focus).testTag("mal_pick_confirm"), primary = true)
            ActionButton("Back", { chosen = null })
        }
        LaunchedEffect(pick) { runCatching { focus.requestFocus() } }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TvTextField(query, { query = it.take(64) }, "Search MyAnimeList", Modifier.width(420.dp), onSubmit = { scope.launch { results = container.mal.search(query).ifEmpty { results } } })
        ActionButton("Search", { scope.launch { results = container.mal.search(query).ifEmpty { results } } }, icon = Icons.Filled.Search)
    }
    results.forEachIndexed { i, anime ->
        TvTile(
            onClick = {
                chosen = anime
                val e = info.episode ?: 1
                episode = if (anime.numEpisodes == 0 || e <= anime.numEpisodes) e else 1
            },
            modifier = (if (i == 0) Modifier.focusRequester(focus) else Modifier).fillMaxWidth().testTag("mal_candidate_${anime.id}"),
            focusedScale = 1.02f,
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(anime, Modifier.width(48.dp).height(68.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(anime.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(anime.mediaType?.uppercase(), anime.year?.toString(), anime.numEpisodes.takeIf { it > 0 }?.let { "$it episodes" }).joinToString(" · "),
                        color = AppColors.TextDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun Cover(anime: MalAnime, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(AppColors.SurfaceHigh)) {
        anime.mainPicture?.let { pic -> AsyncImage(model = pic.medium ?: pic.large, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** MyAnimeList on TV: the account, what's playing, your list, the update settings, matches and the queue. */
@Composable
fun MalHubScreen(container: AppContainer, nav: Navigator) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val state by container.mal.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val account = settings.mal
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("MyAnimeList", style = MaterialTheme.typography.headlineMedium)
        if (account == null) {
            Text("Sign in to keep your MyAnimeList list up to date as you watch.", color = AppColors.TextDim)
            ActionButton("Sign in", { nav.push(Screen.MalLogin) }, Modifier.focusRequester(focus), primary = true)
            return@Column
        }
        var confirmSignOut by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Signed in as ${account.userName}", style = MaterialTheme.typography.titleLarge)
            if (confirmSignOut) {
                Text("Sign out and delete everything kept for MyAnimeList on this TV?", color = AppColors.TextDim)
                ActionButton("Sign out", { confirmSignOut = false; container.mal.signOut() }, Modifier.testTag("mal_sign_out"))
                ActionButton("Cancel", { confirmSignOut = false })
            } else {
                ActionButton("Sign out", { confirmSignOut = true }, Modifier.focusRequester(focus).testTag("mal_hub_sign_out"))
            }
        }
        if (state != MalState.Idle) {
            SectionTitle("Now playing")
            TvMalContent(container, state, remember { FocusRequester() })
        }

        SectionTitle("Your list")
        var status by remember { mutableStateOf(MalStatus.WATCHING) }
        var entries by remember { mutableStateOf<List<Pair<MalAnime, com.yarmiplaytv.media.mal.MalListStatus>>?>(null) }
        var reload by remember { mutableStateOf(0) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(status, reload) {
            entries = runCatching { container.mal.myList(status, fresh = reload > 0) }.getOrDefault(emptyList())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MalStatus.entries.forEach { s -> ActionButton(s.label, { status = s }, primary = s == status) }
        }
        when (val list = entries) {
            null -> Text("Loading…", color = AppColors.TextDim)
            emptyList<Pair<MalAnime, com.yarmiplaytv.media.mal.MalListStatus>>() -> Text("Nothing here.", color = AppColors.TextDim)
            else -> list.forEach { (anime, entry) ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(anime, Modifier.width(44.dp).height(62.dp))
                    Column(Modifier.width(560.dp)) {
                        Text(anime.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val of = if (anime.numEpisodes > 0) " of ${anime.numEpisodes}" else ""
                        Text("${entry.watchedEpisodes}$of watched" + if (entry.score > 0) " · ${entry.score}/10" else "", color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
                    }
                    fun update(u: MalListUpdate) = scope.launch { container.mal.update(anime.copy(myListStatus = entry), u); reload++ }
                    ActionButton("−1", { update(MalListUpdate(watchedEpisodes = (entry.watchedEpisodes - 1).coerceAtLeast(0))) }, enabled = entry.watchedEpisodes > 0)
                    ActionButton("+1", {
                        update(MalService.progressUpdate(entry, entry.watchedEpisodes + 1, anime.numEpisodes, java.time.LocalDate.now().toString()) ?: MalListUpdate(watchedEpisodes = entry.watchedEpisodes + 1))
                    }, enabled = anime.numEpisodes == 0 || entry.watchedEpisodes < anime.numEpisodes)
                }
            }
        }

        SectionTitle("Updating your list")
        ToggleRow("Update my list as I watch", settings.malAutoUpdate,
            { container.scope.launch { container.settingsStore.saveMalPrefs(!settings.malAutoUpdate, settings.malAskFirst) } },
            subtitle = "When an episode is 90% watched; Undo in the player's MyAnimeList panel")
        if (settings.malAutoUpdate) {
            ToggleRow("Ask before each update", settings.malAskFirst,
                { container.scope.launch { container.settingsStore.saveMalPrefs(settings.malAutoUpdate, !settings.malAskFirst) } })
        }
        if (settings.malMappings.isNotEmpty()) {
            SectionTitle("Shows you matched")
            settings.malMappings.forEach { (key, mapping) ->
                ValueRow("MyAnimeList #${mapping.malId}", "Forget", { container.scope.launch { container.settingsStore.saveMalMapping(key, null) } },
                    subtitle = key.substringBefore('|').substringAfterLast('/'))
            }
        }
        if (settings.malIgnored.isNotEmpty()) {
            SectionTitle("Not tracked")
            settings.malIgnored.forEach { key ->
                ValueRow(settings.malIgnoredNames[key]?.ifBlank { null } ?: key.substringAfterLast('/'), "Track again", { container.mal.ignore(key, false) })
            }
        }
        if (settings.malQueue.isNotEmpty()) {
            SectionTitle("Waiting to be sent")
            ValueRow("${settings.malQueue.size} update(s) waiting", "Send now", { scope.launch { container.mal.flushQueue() } })
        }
        Text(MAL_ATTRIBUTION, color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall)
    }
}
