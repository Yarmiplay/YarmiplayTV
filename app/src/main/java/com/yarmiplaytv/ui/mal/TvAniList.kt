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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.yarmiplaytv.media.anilist.AniListAnime
import com.yarmiplaytv.media.anilist.AniListStatus
import com.yarmiplaytv.media.anilist.AniListUpdate
import com.yarmiplaytv.tracking.AniListState
import com.yarmiplaytv.ui.components.ActionButton
import com.yarmiplaytv.ui.components.SectionTitle
import com.yarmiplaytv.ui.components.ToggleRow
import com.yarmiplaytv.ui.components.TvTextField
import com.yarmiplaytv.ui.mobile.ANILIST_PRIVACY_NOTE
import com.yarmiplaytv.ui.mobile.MalSignInPage
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.shared.PRIVACY_POLICY_URL
import com.yarmiplaytv.ui.shared.malInfo
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.launch

/** TV AniList sign-in: remote-controlled WebView intercepting the OAuth Implicit Grant token, plus paste fallback. */
@Composable
fun AniListLoginScreen(container: AppContainer, nav: Navigator) {
    val client = container.anilist.client
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tokenInput by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    fun finish(input: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { container.anilist.signInWithFragmentOrToken(input) }
                .onSuccess {
                    busy = false
                    url = null
                    nav.back()
                }
                .onFailure {
                    busy = false
                    url = null
                    error = it.message ?: "AniList sign-in failed"
                }
        }
    }

    val page = url
    if (page != null && !busy && error == null) {
        MalSignInPage(
            url = page,
            onNavigate = { target ->
                if (target.contains("access_token=")) {
                    finish(target)
                    true
                } else {
                    false
                }
            },
            modifier = Modifier.fillMaxSize(),
            remotePointer = true,
        )
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("AniList", style = MaterialTheme.typography.headlineMedium)
        if (!client.configured) {
            Text("AniList isn't set up in this build of YarmiplayTV.", color = AppColors.TextDim)
            return@Column
        }
        Text(ANILIST_PRIVACY_NOTE, modifier = Modifier.width(900.dp))
        Text("Privacy policy: $PRIVACY_POLICY_URL", color = AppColors.TextDim)
        ActionButton(
            "Continue to AniList",
            { error = null; url = client.authorizeUrl() },
            Modifier.focusRequester(first).testTag("anilist_sign_in"),
            primary = true,
            enabled = !busy,
        )
        error?.let { Text(it, color = AppColors.Error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.width(700.dp)) {
            TvTextField(tokenInput, { tokenInput = it }, "Or paste access token", Modifier.weight(1f), onSubmit = { if (tokenInput.trim().length >= 20) finish(tokenInput) })
            ActionButton("Connect", { finish(tokenInput) }, enabled = !busy && tokenInput.trim().length >= 20)
        }
    }
}

/** TV AniList hub: account, toggles, list tabs, and Find a show. */
@Composable
fun AniListHubScreen(container: AppContainer, nav: Navigator) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val account = settings.anilist
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var tab by rememberSaveable { mutableStateOf(AniListStatus.CURRENT) }
    var list by remember { mutableStateOf<List<AniListAnime>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<AniListAnime>?>(null) }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun loadList(st: AniListStatus = tab) {
        if (account == null) return
        list = null
        error = null
        scope.launch {
            runCatching { container.anilist.myList(st) }
                .onSuccess { list = it }
                .onFailure { error = it.message ?: "Couldn't load AniList" }
        }
    }

    LaunchedEffect(account?.userId, tab) { if (account != null) loadList(tab) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 64.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("AniList", style = MaterialTheme.typography.headlineMedium)
        if (!container.anilist.client.configured) {
            Text("AniList isn't set up in this build.", color = AppColors.TextDim)
            return@Column
        }
        if (account == null) {
            Text("Sign in to keep your AniList up to date as you watch.", color = AppColors.TextDim)
            ActionButton("Sign in", { nav.push(Screen.AniListLogin) }, Modifier.focusRequester(focus), primary = true)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Signed in as ${account.userName}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            ActionButton("Sign out", { container.anilist.signOut() }, Modifier.focusRequester(focus))
        }
        ToggleRow("Update my list as I watch", settings.anilistAutoUpdate, {
            container.scope.launch { container.settingsStore.saveAniListPrefs(!settings.anilistAutoUpdate, settings.anilistAskFirst) }
        })
        ToggleRow("Ask before each update", settings.anilistAskFirst, {
            container.scope.launch { container.settingsStore.saveAniListPrefs(settings.anilistAutoUpdate, !settings.anilistAskFirst) }
        })
        SectionTitle("Your list")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(AniListStatus.CURRENT, AniListStatus.PLANNING, AniListStatus.COMPLETED).forEach { st ->
                ActionButton(st.label, { tab = st }, primary = tab == st)
            }
        }
        error?.let { Text(it, color = AppColors.Error) }
        if (list == null && error == null) Text("Loading…", color = AppColors.TextDim)
        list?.forEach { anime ->
            TvAniListRow(container, anime, onUpdated = { loadList() })
        }
        if (list?.isEmpty() == true) Text("Nothing here.", color = AppColors.TextDim)

        SectionTitle("Find a show")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            TvTextField(query, { query = it.take(64) }, "Search AniList", Modifier.weight(1f), onSubmit = {
                scope.launch { searchResults = runCatching { container.anilist.findShows(query) }.getOrDefault(emptyList()) }
            })
            ActionButton("Search", {
                scope.launch { searchResults = runCatching { container.anilist.findShows(query) }.getOrDefault(emptyList()) }
            })
        }
        searchResults?.forEach { anime -> TvAniListRow(container, anime, onUpdated = { loadList() }) }
    }
}

@Composable
private fun TvAniListRow(container: AppContainer, anime: AniListAnime, onUpdated: () -> Unit) {
    val scope = rememberCoroutineScope()
    val entry = anime.listEntry
    val progress = entry?.progress ?: 0
    val of = if (anime.episodes > 0) " of ${anime.episodes}" else ""
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(44.dp).height(62.dp).clip(RoundedCornerShape(6.dp)).background(AppColors.SurfaceHigh)) {
            anime.coverUrl?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f)) {
            Text(anime.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$progress$of watched" + if ((entry?.score ?: 0.0) > 0.0) " · ${entry?.score}" else "", color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        ActionButton("−1", {
            scope.launch { container.anilist.update(anime, AniListUpdate(progress = (progress - 1).coerceAtLeast(0))); onUpdated() }
        }, enabled = progress > 0)
        ActionButton("+1", {
            val next = progress + 1
            val finished = anime.episodes > 0 && next >= anime.episodes
            val status = if (finished) AniListStatus.COMPLETED else entry?.statusValue ?: AniListStatus.CURRENT
            scope.launch { container.anilist.update(anime, AniListUpdate(status = status, progress = next)); onUpdated() }
        }, enabled = anime.episodes == 0 || progress < anime.episodes)
    }
}

@Composable
fun AniListEntryScreen(container: AppContainer, nav: Navigator, item: MediaItem) {
    val info = remember(item) { item.malInfo() }
    var state by remember { mutableStateOf<AniListState?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    fun reload() {
        if (info != null) scope.launch { state = container.anilist.lookup(info) }
    }
    LaunchedEffect(info) { reload() }
    LaunchedEffect(state != null) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 40.dp)) {
        state?.let { TvAniListContent(container, it, focus, onChanged = ::reload, onSignIn = { nav.push(Screen.AniListLogin) }) }
            ?: Text(if (info == null) "This isn't a show." else "Looking it up…", color = AppColors.TextDim)
    }
}

/** TV player panel wrapping MyAnimeList and/or AniList with a D-pad switcher when both are configured. */
@Composable
fun TrackerPanel(container: AppContainer, focus: FocusRequester) {
    val malOn = container.mal.client.configured
    val alOn = container.anilist.client.configured
    var useAniList by rememberSaveable { mutableStateOf(!malOn || (!container.mal.signedIn && container.anilist.signedIn)) }

    if (malOn && alOn) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("MyAnimeList", { useAniList = false }, primary = !useAniList)
                ActionButton("AniList", { useAniList = true }, primary = useAniList)
            }
            Box(Modifier.weight(1f)) {
                if (useAniList) {
                    val state by container.anilist.state.collectAsStateWithLifecycle()
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                        TvAniListContent(container, state, focus)
                    }
                } else {
                    MalPanel(container, focus)
                }
            }
        }
    } else if (alOn) {
        val state by container.anilist.state.collectAsStateWithLifecycle()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            TvAniListContent(container, state, focus)
        }
    } else {
        MalPanel(container, focus)
    }
}

@Composable
private fun TvAniListContent(
    container: AppContainer,
    state: AniListState,
    focus: FocusRequester,
    onChanged: () -> Unit = {},
    onSignIn: (() -> Unit)? = null,
) {
    val al = container.anilist
    val scope = rememberCoroutineScope()
    var picking by remember(state) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("anilist_content")) {
        Text("AniList", style = MaterialTheme.typography.headlineSmall)
        when (state) {
            AniListState.NotConfigured -> Text("AniList isn't set up in this build.", color = AppColors.TextDim)
            AniListState.SignedOut -> {
                Text("Sign in to AniList in Settings to keep your list up to date as you watch.", color = AppColors.TextDim)
                onSignIn?.let { ActionButton("Sign in", it, Modifier.focusRequester(focus)) }
            }
            AniListState.Idle -> Text("This isn't an episode of a show.", color = AppColors.TextDim)
            is AniListState.Matching -> Text("Finding ${state.info.seriesName}…", color = AppColors.TextDim)
            is AniListState.Failed -> {
                Text(state.message, color = AppColors.Error)
                ActionButton("Try again", { al.refresh(); onChanged() }, Modifier.focusRequester(focus))
            }
            is AniListState.Ignored -> {
                Text("${state.info.seriesName} isn't tracked on AniList.", color = AppColors.TextDim)
                ActionButton("Track this show", { al.unignore(state.info.seriesKey); onChanged() }, Modifier.focusRequester(focus))
            }
            is AniListState.Unsure -> TvAniListPicker(container, state.info, state.candidates, focus, onChanged)
            is AniListState.Matched -> if (picking) {
                TvAniListPicker(container, state.info, state.match.candidates.ifEmpty { listOf(state.entry) }, focus, onChanged)
                ActionButton("Keep ${state.entry.title}", { picking = false })
            } else {
                val anime = state.entry
                val entry = anime.listEntry
                val watched = entry?.progress ?: 0
                val of = if (anime.episodes > 0) " of ${anime.episodes}" else ""
                Text(anime.title, style = MaterialTheme.typography.titleMedium)
                Text("This is episode ${state.match.episode}$of", color = AppColors.TextDim)
                Text(
                    entry?.let { "Your list: ${it.statusValue?.label ?: "—"} · ${it.progress}$of watched" + (if (it.score > 0) " · ${it.score}" else "") }
                        ?: "Not on your list yet",
                    color = AppColors.TextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("−1", {
                        scope.launch { al.update(anime, AniListUpdate(progress = (watched - 1).coerceAtLeast(0))); onChanged() }
                    }, enabled = watched > 0)
                    ActionButton("+1", {
                        scope.launch { al.update(anime, AniListUpdate(progress = watched + 1)); onChanged() }
                    }, enabled = anime.episodes == 0 || watched < anime.episodes)
                    ActionButton("Mark ep ${state.match.episode} watched", { al.watched(state.info); onChanged() }, Modifier.focusRequester(focus), primary = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Wrong entry?", { picking = true })
                    ActionButton("Don't track", { al.ignore(state.info); onChanged() })
                }
            }
        }
    }
}

@Composable
private fun TvAniListPicker(
    container: AppContainer,
    info: EpisodeInfo,
    candidates: List<AniListAnime>,
    focus: FocusRequester,
    onChanged: () -> Unit,
) {
    var results by remember(candidates) { mutableStateOf(candidates) }
    var query by remember(info) { mutableStateOf(info.seriesName) }
    val scope = rememberCoroutineScope()
    Text("Which AniList entry is ${info.seriesName} ep ${info.episode ?: "?"}?")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        TvTextField(query, { query = it.take(64) }, "Search AniList", Modifier.weight(1f), onSubmit = {
            scope.launch { results = runCatching { container.anilist.findShows(query) }.getOrDefault(emptyList()).ifEmpty { results } }
        })
        ActionButton("Search", {
            scope.launch { results = runCatching { container.anilist.findShows(query) }.getOrDefault(emptyList()).ifEmpty { results } }
        })
    }
    results.take(8).forEachIndexed { idx, anime ->
        ActionButton(
            anime.title,
            {
                val ep = info.episode ?: 1
                val target = if (anime.episodes == 0 || ep <= anime.episodes) ep else 1
                container.anilist.choose(info, anime, target)
                onChanged()
            },
            if (idx == 0) Modifier.focusRequester(focus) else Modifier,
        )
    }
}
