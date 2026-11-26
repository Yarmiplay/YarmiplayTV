package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.media.EpisodeInfo
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.anilist.AniListAnime
import com.yarmiplaytv.media.anilist.AniListClient
import com.yarmiplaytv.media.anilist.AniListStatus
import com.yarmiplaytv.media.anilist.AniListUpdate
import com.yarmiplaytv.tracking.AniListCallbackServer
import com.yarmiplaytv.tracking.AniListState
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.shared.PRIVACY_POLICY_URL
import com.yarmiplaytv.ui.shared.malInfo
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.launch

const val ANILIST_PRIVACY_NOTE =
    "YarmiplayTV connects directly to AniList (graphql.anilist.co) using OAuth without storing your password. " +
        "Your access token stays only on this device and is deleted when you sign out."

/** Signing in to AniList using OAuth 2 Implicit Grant (in-app WebView on Android, localhost loopback on Desktop). */
@Composable
fun MobileAniListLoginScreen(container: AppContainer, nav: Navigator) {
    val client = container.anilist.client
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var manualToken by remember { mutableStateOf("") }

    fun finishWithInput(input: String) {
        if (busy) return
        busy = true
        error = null
        status = "Finishing sign-in…"
        scope.launch {
            runCatching { container.anilist.signInWithFragmentOrToken(input) }
                .onSuccess {
                    busy = false
                    status = null
                    page = null
                    nav.back()
                }
                .onFailure {
                    busy = false
                    status = null
                    page = null
                    error = it.message ?: "AniList sign-in failed"
                }
        }
    }

    val signingIn = page?.takeIf { !busy && error == null }
    if (signingIn != null) {
        PlatformBackHandler { page = null }
        Column(Modifier.fillMaxSize()) {
            MobileTopBar("AniList", nav, showBack = true, onBack = { page = null })
            MalSignInPage(
                url = signingIn,
                onNavigate = { target ->
                    if (target.contains("access_token=")) {
                        finishWithInput(target)
                        true
                    } else {
                        false
                    }
                },
                modifier = Modifier.fillMaxSize().imePadding().testTag("anilist_page"),
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        MobileTopBar("AniList", nav)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FormColumn {
                if (!client.configured) {
                    Text("AniList isn't set up in this build of YarmiplayTV.", color = AppColors.TextDim)
                    return@FormColumn
                }
                Text(ANILIST_PRIVACY_NOTE)
                TextButton({ uriHandler.openUri(PRIVACY_POLICY_URL) }) { Text("Privacy policy") }
                Button(
                    onClick = {
                        error = null
                        if (malSignInInApp) {
                            page = client.authorizeUrl()
                        } else {
                            status = "Waiting for AniList sign-in in your browser…"
                            scope.launch {
                                val authUrl = client.authorizeUrl(AniListClient.CALLBACK_URL)
                                val waiter = launch {
                                    val frag = runCatching { AniListCallbackServer.awaitFragment() }.getOrNull()
                                    if (frag != null) finishWithInput(frag)
                                    else if (status != null) {
                                        status = null
                                        error = "Sign-in timed out or was cancelled."
                                    }
                                }
                                runCatching { uriHandler.openUri(authUrl) }.onFailure {
                                    waiter.cancel()
                                    status = null
                                    error = "Couldn't open browser: ${it.message}"
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag("anilist_sign_in"),
                ) { Text("Sign in on AniList") }
                status?.let { Text(it, color = AppColors.TextDim) }
                error?.let { Text(it, color = AppColors.Error) }
                Spacer(Modifier.height(12.dp))
                Text("Or paste an AniList access token / redirect URL:", color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualToken,
                        onValueChange = { manualToken = it },
                        label = { Text("Token or URL") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).testTag("anilist_token_field"),
                    )
                    OutlinedButton(
                        onClick = { finishWithInput(manualToken) },
                        enabled = !busy && manualToken.trim().length >= 20,
                    ) { Text("Connect") }
                }
            }
        }
    }
}

/** Main AniList hub: account, auto-update toggles, Find a show, My List, and ignored shows. */
@Composable
fun MobileAniListScreen(container: AppContainer, nav: Navigator) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val account = settings.anilist
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(AniListStatus.CURRENT) }
    var list by remember { mutableStateOf<List<AniListAnime>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<AniListAnime>?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }

    fun loadList(st: AniListStatus = tab) {
        if (account == null) return
        list = null
        listError = null
        scope.launch {
            runCatching { container.anilist.myList(st) }
                .onSuccess { list = it }
                .onFailure { listError = it.message ?: "Couldn't load AniList" }
        }
    }

    LaunchedEffect(account?.userId, tab) {
        if (account != null) loadList(tab)
    }

    Column(Modifier.fillMaxSize()) {
        MobileTopBar("AniList", nav)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!container.anilist.client.configured) {
                Text("AniList isn't set up in this build of YarmiplayTV.", color = AppColors.TextDim)
                return@Column
            }
            if (account == null) {
                Surface(color = AppColors.Surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Sign in to AniList to keep your anime list up to date as you watch.")
                        Button({ nav.push(Screen.AniListLogin) }, Modifier.testTag("anilist_hub_sign_in")) { Text("Sign in") }
                    }
                }
            } else {
                Surface(color = AppColors.Surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            account.avatarUrl?.let { url ->
                                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(account.userName, style = MaterialTheme.typography.titleMedium)
                                Text("Score format: ${account.scoreFormat.lowercase().replace('_', ' ')}", color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton({ container.anilist.signOut() }, Modifier.testTag("anilist_sign_out")) { Text("Sign out") }
                        }
                        SettingsToggle(
                            "Update my list as I watch",
                            "Marks an episode watched on AniList after 90% of it has played",
                            settings.anilistAutoUpdate,
                            Modifier.testTag("toggle_anilist_auto"),
                        ) { on -> container.scope.launch { container.settingsStore.saveAniListPrefs(on, settings.anilistAskFirst) } }
                        SettingsToggle(
                            "Ask before each update",
                            "Show a prompt before sending an episode update to AniList",
                            settings.anilistAskFirst,
                            Modifier.testTag("toggle_anilist_ask"),
                        ) { on -> container.scope.launch { container.settingsStore.saveAniListPrefs(settings.anilistAutoUpdate, on) } }
                        if (settings.anilistQueue.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${settings.anilistQueue.size} pending update(s)", color = AppColors.TextDim, modifier = Modifier.weight(1f))
                                OutlinedButton({ scope.launch { container.anilist.flushQueue() } }) { Text("Send now") }
                            }
                        }
                    }
                }
            }

            // Find a show
            Surface(color = AppColors.Surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Find a show", style = MaterialTheme.typography.titleMedium)
                    fun doSearch() {
                        if (query.trim().length < 2) return
                        searchError = null
                        scope.launch {
                            runCatching { container.anilist.findShows(query.trim()) }
                                .onSuccess { searchResults = it }
                                .onFailure { searchError = it.message ?: "Search failed" }
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(64) },
                        label = { Text("Search AniList") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                        trailingIcon = { IconButton(::doSearch) { Icon(Icons.Filled.Search, "Search") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    searchError?.let { Text(it, color = AppColors.Error, style = MaterialTheme.typography.bodySmall) }
                    searchResults?.forEach { anime ->
                        AniListRow(container, anime, onUpdated = { loadList() })
                    }
                    if (searchResults?.isEmpty() == true) Text("Nothing found.", color = AppColors.TextDim)
                }
            }

            // My List
            if (account != null) {
                Surface(color = AppColors.Surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Your list", style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(AniListStatus.CURRENT, AniListStatus.PLANNING, AniListStatus.COMPLETED, AniListStatus.PAUSED, AniListStatus.DROPPED).forEach { st ->
                                FilterChip(selected = tab == st, onClick = { tab = st }, label = { Text(st.label) })
                            }
                        }
                        listError?.let { Text(it, color = AppColors.Error) }
                        if (list == null && listError == null) CircularProgressIndicator(Modifier.size(24.dp))
                        list?.forEach { anime ->
                            AniListRow(container, anime, onUpdated = { loadList() })
                        }
                        if (list?.isEmpty() == true) Text("Nothing here.", color = AppColors.TextDim)
                    }
                }
            }

            // Ignored shows
            if (settings.anilistIgnored.isNotEmpty()) {
                Surface(color = AppColors.Surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Shows you don't track", style = MaterialTheme.typography.titleMedium)
                        settings.anilistIgnored.forEach { key ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(settings.anilistIgnoredNames[key] ?: key, Modifier.weight(1f))
                                TextButton({ container.anilist.unignore(key) }) { Text("Track again") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AniListRow(container: AppContainer, anime: AniListAnime, onUpdated: () -> Unit) {
    val scope = rememberCoroutineScope()
    val entry = anime.listEntry
    val progress = entry?.progress ?: 0
    val of = if (anime.episodes > 0) " / ${anime.episodes}" else ""
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.SurfaceHigh).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AniListCover(anime, Modifier.width(42.dp).height(60.dp))
        Column(Modifier.weight(1f)) {
            Text(anime.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            val meta = buildList {
                entry?.statusValue?.label?.let(::add)
                add("$progress$of ep")
                if ((entry?.score ?: 0.0) > 0.0) add("★ ${entry?.score}")
            }.joinToString(" · ")
            Text(meta, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        if (container.anilist.signedIn) {
            IconButton(
                onClick = { scope.launch { container.anilist.update(anime, AniListUpdate(progress = (progress - 1).coerceAtLeast(0))); onUpdated() } },
                enabled = progress > 0,
            ) { Icon(Icons.Filled.Remove, "One episode fewer") }
            IconButton(
                onClick = {
                    val next = progress + 1
                    val finished = anime.episodes > 0 && next >= anime.episodes
                    val status = if (finished) AniListStatus.COMPLETED else entry?.statusValue ?: AniListStatus.CURRENT
                    scope.launch { container.anilist.update(anime, AniListUpdate(status = status, progress = next)); onUpdated() }
                },
                enabled = anime.episodes == 0 || progress < anime.episodes,
            ) { Icon(Icons.Filled.Add, "One episode more") }
        }
    }
}

/** A show from the library on AniList: match it, set its status or score, without playing it. */
@Composable
fun MobileAniListEntryScreen(container: AppContainer, nav: Navigator, item: MediaItem) {
    val info = remember(item) { item.malInfo() }
    var state by remember { mutableStateOf<AniListState?>(null) }
    val scope = rememberCoroutineScope()
    fun reload() {
        if (info != null) scope.launch { state = container.anilist.lookup(info) }
    }
    LaunchedEffect(info) { reload() }
    Column(Modifier.fillMaxSize()) {
        MobileTopBar("AniList", nav)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 24.dp)) {
            when {
                info == null -> Text("This isn't a show.", Modifier.padding(16.dp), color = AppColors.TextDim)
                state == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                else -> AniListContent(container, state!!, onChanged = ::reload, onSignIn = { nav.push(Screen.AniListLogin) })
            }
        }
    }
}

/** The AniList player/entry panel, mirroring [MalContent]. */
@Composable
fun AniListContent(
    container: AppContainer,
    state: AniListState,
    modifier: Modifier = Modifier,
    onChanged: () -> Unit = {},
    onSignIn: (() -> Unit)? = null,
) {
    val al = container.anilist
    var picking by remember(state) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("anilist_content"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AniList", style = MaterialTheme.typography.titleLarge)
        when (state) {
            AniListState.NotConfigured -> Text("AniList isn't set up in this build.", color = AppColors.TextDim)
            AniListState.SignedOut -> {
                Text("Sign in to AniList in Settings to keep your list up to date as you watch.", color = AppColors.TextDim)
                onSignIn?.let { OutlinedButton(it) { Text("Sign in") } }
            }
            AniListState.Idle -> Text("This isn't an episode of a show.", color = AppColors.TextDim)
            is AniListState.Matching -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Finding ${state.info.seriesName}…", color = AppColors.TextDim)
            }
            is AniListState.Failed -> {
                Text(state.message, color = AppColors.Error)
                OutlinedButton({ al.refresh(); onChanged() }) { Text("Try again") }
            }
            is AniListState.Ignored -> {
                Text("${state.info.seriesName} isn't tracked on AniList.", color = AppColors.TextDim)
                OutlinedButton({ al.unignore(state.info.seriesKey); onChanged() }) { Text("Track this show") }
            }
            is AniListState.Unsure -> AniListPicker(container, state.info, state.candidates, onChanged)
            is AniListState.Matched -> if (picking) {
                AniListPicker(container, state.info, state.match.candidates.ifEmpty { listOf(state.entry) }, onChanged)
                TextButton({ picking = false }) { Text("Keep ${state.entry.title}") }
            } else {
                AniListEntry(container, state, onChanged, onWrongEntry = { picking = true })
            }
        }
    }
}

@Composable
private fun AniListEntry(container: AppContainer, state: AniListState.Matched, onChanged: () -> Unit, onWrongEntry: () -> Unit) {
    val al = container.anilist
    val anime = state.entry
    val list = anime.listEntry
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    Row(verticalAlignment = Alignment.Top) {
        AniListCover(anime, Modifier.width(84.dp).height(120.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("anilist_title"))
            val of = if (anime.episodes > 0) " of ${anime.episodes}" else ""
            Text("This is episode ${state.match.episode}$of", color = AppColors.TextDim)
            Text(
                list?.let { "Your list: ${it.statusValue?.label ?: "—"} · ${it.progress}$of watched" + (if (it.score > 0) " · ${it.score}" else "") }
                    ?: "Not on your list yet",
                color = AppColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    fun edit(update: AniListUpdate) {
        scope.launch {
            al.update(anime, update)
            onChanged()
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Watched", Modifier.weight(1f))
        val watched = list?.progress ?: 0
        IconButton({ edit(AniListUpdate(progress = (watched - 1).coerceAtLeast(0))) }, enabled = watched > 0) {
            Icon(Icons.Filled.Remove, "One episode fewer")
        }
        Text("$watched", style = MaterialTheme.typography.titleMedium)
        IconButton(
            { edit(AniListUpdate(progress = watched + 1)) },
            enabled = anime.episodes == 0 || watched < anime.episodes,
        ) { Icon(Icons.Filled.Add, "One episode more") }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var statusOpen by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f)) {
            OutlinedButton({ statusOpen = true }, Modifier.fillMaxWidth()) {
                Text("Status: ${list?.statusValue?.label ?: "Not set"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(statusOpen, { statusOpen = false }) {
                AniListStatus.entries.forEach { st ->
                    DropdownMenuItem({ Text(st.label) }, { statusOpen = false; edit(AniListUpdate(status = st)) })
                }
            }
        }
        var scoreOpen by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f)) {
            OutlinedButton({ scoreOpen = true }, Modifier.fillMaxWidth()) {
                Text("Score: ${list?.score?.takeIf { it > 0 }?.let { "$it" } ?: "—"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(scoreOpen, { scoreOpen = false }) {
                (10 downTo 1).forEach { s ->
                    DropdownMenuItem({ Text("$s") }, { scoreOpen = false; edit(AniListUpdate(score = s.toDouble())) })
                }
                DropdownMenuItem({ Text("No score") }, { scoreOpen = false; edit(AniListUpdate(score = 0.0)) })
            }
        }
    }

    Button({ al.watched(state.info); onChanged() }) { Text("Mark episode ${state.match.episode} watched") }
    val canUndo by al.canUndo.collectAsStateWithLifecycle()
    if (canUndo) OutlinedButton({ al.undo(); onChanged() }) { Text("Undo the last update") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onWrongEntry) { Text("Wrong entry?") }
        TextButton({ al.ignore(state.info); onChanged() }) { Text("Don't track this show") }
    }
    TextButton({ uriHandler.openUri(anime.url) }) { Text("Open on AniList") }
}

@Composable
private fun AniListPicker(container: AppContainer, info: EpisodeInfo, candidates: List<AniListAnime>, onChanged: () -> Unit) {
    var query by remember(info) { mutableStateOf(info.seriesName) }
    var results by remember(candidates) { mutableStateOf(candidates) }
    var chosen by remember(info) { mutableStateOf<AniListAnime?>(null) }
    var episode by remember(info) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val season = info.season?.let { "season $it " }.orEmpty()
    Text("Which AniList entry is ${info.seriesName} ${season}episode ${info.episode ?: "?"}?")
    val pick = chosen
    if (pick != null) {
        Text(pick.title, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            episode, { episode = it.filter(Char::isDigit).take(4) },
            label = { Text("Which of its episodes" + if (pick.episodes > 0) " (1–${pick.episodes})" else "") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                { episode.toIntOrNull()?.let { container.anilist.choose(info, pick, it); onChanged() } },
                enabled = episode.toIntOrNull()?.let { it >= 1 && (pick.episodes == 0 || it <= pick.episodes) } == true,
            ) { Text("That's it") }
            TextButton({ chosen = null }) { Text("Back") }
        }
        return
    }
    fun search() {
        scope.launch { results = runCatching { container.anilist.findShows(query) }.getOrDefault(emptyList()).ifEmpty { results } }
    }
    OutlinedTextField(
        query, { query = it.take(64) },
        label = { Text("Search AniList") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { search() }),
        trailingIcon = { IconButton(::search) { Icon(Icons.Filled.Search, "Search") } },
        modifier = Modifier.fillMaxWidth(),
    )
    results.forEach { anime ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                chosen = anime
                val e = info.episode ?: 1
                episode = (if (anime.episodes == 0 || e <= anime.episodes) e else 1).toString()
            }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AniListCover(anime, Modifier.width(40.dp).height(56.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(anime.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(anime.format, anime.seasonYear?.toString(), anime.episodes.takeIf { it > 0 }?.let { "$it episodes" }).joinToString(" · "),
                    color = AppColors.TextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AniListCover(anime: AniListAnime, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(AppColors.SurfaceHigh)) {
        anime.coverUrl?.let { url ->
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Combined MyAnimeList + AniList switcher for player sheets and the desktop side panel. */
@Composable
fun TrackerPanelContent(
    container: AppContainer,
    onSignInMal: (() -> Unit)? = null,
    onSignInAniList: (() -> Unit)? = null,
) {
    val malOn = container.mal.client.configured
    val alOn = container.anilist.client.configured
    var useAniList by rememberSaveable { mutableStateOf(!malOn || (!container.mal.signedIn && container.anilist.signedIn)) }

    if (malOn && alOn) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilterChip(
                selected = !useAniList,
                onClick = { useAniList = false },
                label = { Text("MyAnimeList") },
                modifier = Modifier.testTag("tracker_tab_mal"),
            )
            FilterChip(
                selected = useAniList,
                onClick = { useAniList = true },
                label = { Text("AniList") },
                modifier = Modifier.testTag("tracker_tab_anilist"),
            )
        }
        Spacer(Modifier.height(4.dp))
    }

    if (useAniList && alOn) {
        val al by container.anilist.state.collectAsStateWithLifecycle()
        AniListContent(container, al, onSignIn = onSignInAniList)
    } else {
        val mal by container.mal.state.collectAsStateWithLifecycle()
        MalContent(container, mal, onSignIn = onSignInMal)
        Spacer(Modifier.height(16.dp))
        MalFindSection(container)
    }
}
