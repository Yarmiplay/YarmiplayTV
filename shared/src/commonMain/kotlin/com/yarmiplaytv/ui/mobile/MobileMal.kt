package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.yarmiplaytv.media.mal.MalAnime
import com.yarmiplaytv.media.mal.MalListStatus
import com.yarmiplaytv.media.mal.MalListUpdate
import com.yarmiplaytv.media.mal.MalStatus
import com.yarmiplaytv.syncplay.ConnectionStatus
import com.yarmiplaytv.tracking.AvailableEpisode
import com.yarmiplaytv.tracking.MalService
import com.yarmiplaytv.tracking.MalState
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.shared.MAL_ATTRIBUTION
import com.yarmiplaytv.ui.shared.MAL_PRIVACY_NOTE
import com.yarmiplaytv.ui.shared.MalSearchModel
import com.yarmiplaytv.ui.shared.PRIVACY_POLICY_URL
import com.yarmiplaytv.ui.shared.addEpisodeToPlaylist
import com.yarmiplaytv.ui.shared.details
import com.yarmiplaytv.ui.shared.listSummary
import com.yarmiplaytv.ui.shared.malInfo
import com.yarmiplaytv.ui.shared.nextEpisode
import com.yarmiplaytv.ui.shared.playEpisode
import com.yarmiplaytv.ui.shared.rememberMalLoginModel
import com.yarmiplaytv.ui.shared.rememberMalSearchModel
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.ui.theme.SurfaceInk
import com.yarmiplaytv.ui.theme.OnVideo
import kotlinx.coroutines.launch

/** Signing in to MyAnimeList: what's shared and kept first, then MyAnimeList's own page in the browser. */
@Composable
fun MobileMalLoginScreen(container: AppContainer, nav: Navigator) {
    val model = rememberMalLoginModel(container)
    val uriHandler = LocalUriHandler.current
    var page by remember { mutableStateOf<String?>(null) }
    val signingIn = page?.takeIf { model.status == null && model.error == null }
    if (signingIn != null) {
        // Back leaves MyAnimeList's page for the screen before it.
        PlatformBackHandler { page = null }
        Column(Modifier.fillMaxSize()) {
            MobileTopBar("MyAnimeList", nav, showBack = true, onBack = { page = null })
            MalSignInPage(signingIn, { target -> model.onRedirect(target) { nav.back() } }, Modifier.fillMaxSize().imePadding().testTag("mal_page"))
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        MobileTopBar("MyAnimeList", nav)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FormColumn {
                if (!model.configured) {
                    Text("MyAnimeList isn't set up in this build of YarmiplayTV.", color = AppColors.TextDim)
                    return@FormColumn
                }
                Text(MAL_PRIVACY_NOTE)
                TextButton({ uriHandler.openUri(PRIVACY_POLICY_URL) }, Modifier.testTag("mal_privacy")) { Text("Privacy policy") }
                Button(
                    {
                        if (malSignInInApp) page = model.begin()
                        else model.signInWithBrowser(uriHandler::openUri) { nav.back() }
                    },
                    enabled = !model.busy,
                    modifier = Modifier.testTag("mal_sign_in"),
                ) { Text("Sign in on MyAnimeList") }
                model.status?.let { Text(it, color = AppColors.TextDim) }
                model.error?.let { Text(it, color = AppColors.Error) }
                if (model.status != null && !model.busy) TextButton(model::cancel) { Text("Cancel") }
                Text(MAL_ATTRIBUTION, color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** A show from the library on MyAnimeList: match it, set its status or score, without playing it. */
@Composable
fun MobileMalEntryScreen(container: AppContainer, nav: Navigator, item: MediaItem) {
    val info = remember(item) { item.malInfo() }
    var state by remember { mutableStateOf<MalState?>(null) }
    val scope = rememberCoroutineScope()
    fun reload() {
        if (info != null) scope.launch { state = container.mal.lookup(info) }
    }
    LaunchedEffect(info) { reload() }
    Column(Modifier.fillMaxSize()) {
        MobileTopBar("MyAnimeList", nav)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 24.dp)) {
            when {
                info == null -> Text("This isn't a show.", Modifier.padding(16.dp), color = AppColors.TextDim)
                state == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                else -> MalContent(container, state!!, onChanged = ::reload, onSignIn = { nav.push(Screen.MalLogin) })
            }
        }
    }
}

/**
 * The MyAnimeList panel: the matched entry with your list status (to change by hand), or the picker when it isn't
 * sure which entry this is. [onChanged] runs after a change, for views that don't follow the playing episode.
 */
@Composable
fun MalContent(container: AppContainer, state: MalState, modifier: Modifier = Modifier, onChanged: () -> Unit = {}, onSignIn: (() -> Unit)? = null) {
    val mal = container.mal
    var picking by remember(state) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("mal_content"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("MyAnimeList", style = MaterialTheme.typography.titleLarge)
        when (state) {
            MalState.NotConfigured -> Text("MyAnimeList isn't set up in this build.", color = AppColors.TextDim)
            MalState.SignedOut -> {
                Text("Sign in to MyAnimeList in Settings to keep your list up to date as you watch.", color = AppColors.TextDim)
                onSignIn?.let { OutlinedButton(it) { Text("Sign in") } }
            }
            MalState.Idle -> Text("This isn't an episode of a show.", color = AppColors.TextDim)
            is MalState.Matching -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Finding ${state.info.seriesName}…", color = AppColors.TextDim)
            }
            is MalState.Failed -> {
                Text(state.message, color = AppColors.Error)
                OutlinedButton({ mal.refresh(); onChanged() }) { Text("Try again") }
            }
            is MalState.Ignored -> {
                Text("${state.info.seriesName} isn't tracked.", color = AppColors.TextDim)
                OutlinedButton({ mal.ignore(state.info.seriesKey, false); onChanged() }, Modifier.testTag("mal_track")) { Text("Track this show") }
            }
            is MalState.Unsure -> MalPicker(container, state.info, state.candidates, onChanged)
            is MalState.Matched -> if (picking) {
                MalPicker(container, state.info, state.match.candidates.ifEmpty { listOf(state.entry) }, onChanged)
                TextButton({ picking = false }) { Text("Keep ${state.entry.title}") }
            } else {
                MalEntry(container, state, onChanged, onWrongEntry = { picking = true })
            }
        }
        Text(MAL_ATTRIBUTION, color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MalEntry(container: AppContainer, state: MalState.Matched, onChanged: () -> Unit, onWrongEntry: () -> Unit) {
    val mal = container.mal
    val anime = state.entry
    val list = anime.myListStatus
    val uriHandler = LocalUriHandler.current
    Row(verticalAlignment = Alignment.Top) {
        Cover(anime, Modifier.width(84.dp).height(120.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("mal_title"))
            val of = if (anime.numEpisodes > 0) " of ${anime.numEpisodes}" else ""
            Text("This is episode ${state.match.episode}$of", color = AppColors.TextDim, modifier = Modifier.testTag("mal_episode"))
            Text(
                list?.let { "Your list: ${it.statusValue?.label ?: "—"} · ${it.watchedEpisodes}$of watched" + (if (it.score > 0) " · ${it.score}/10" else "") }
                    ?: "Not on your list yet",
                color = AppColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("mal_list_status"),
            )
        }
    }
    fun edit(update: MalListUpdate) {
        mal.edit(anime, update)
        onChanged()
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Watched", Modifier.weight(1f))
        val watched = list?.watchedEpisodes ?: 0
        IconButton({ edit(MalListUpdate(watchedEpisodes = (watched - 1).coerceAtLeast(0))) }, enabled = watched > 0, modifier = Modifier.testTag("mal_minus")) {
            Icon(Icons.Filled.Remove, "One episode fewer")
        }
        Text("$watched", style = MaterialTheme.typography.titleMedium)
        IconButton(
            { edit(MalListUpdate(watchedEpisodes = watched + 1)) },
            enabled = anime.numEpisodes == 0 || watched < anime.numEpisodes,
            modifier = Modifier.testTag("mal_plus"),
        ) { Icon(Icons.Filled.Add, "One episode more") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("Status", list?.statusValue?.label ?: "Not set", MalStatus.entries.map { it.label }, Modifier.weight(1f).testTag("mal_status")) { i ->
            edit(MalListUpdate(status = MalStatus.entries[i]))
        }
        Choice("Score", list?.score?.takeIf { it > 0 }?.let { "$it/10" } ?: "Not rated", (10 downTo 1).map { "$it" } + "No score", Modifier.weight(1f).testTag("mal_score")) { i ->
            edit(MalListUpdate(score = if (i == 10) 0 else 10 - i))
        }
    }
    Button({ container.scope.launch { mal.record(anime, state.match.episode, automatic = true); onChanged() } }, Modifier.testTag("mal_mark_watched")) {
        Text("Mark episode ${state.match.episode} watched")
    }
    val canUndo by mal.canUndo.collectAsStateWithLifecycle()
    if (canUndo) OutlinedButton({ mal.undo(); onChanged() }, Modifier.testTag("mal_undo")) { Text("Undo the last update") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onWrongEntry, Modifier.testTag("mal_wrong_entry")) { Text("Wrong entry?") }
        TextButton({ mal.ignore(state.info.seriesKey, true, state.info.seriesName); onChanged() }, Modifier.testTag("mal_ignore")) { Text("Don't track this show") }
    }
    TextButton({ uriHandler.openUri(anime.url) }) { Text("Open on MyAnimeList") }
}

/** Which entry (and which of its episodes) [info] is: the likely ones, or a search. */
@Composable
private fun MalPicker(container: AppContainer, info: EpisodeInfo, candidates: List<MalAnime>, onChanged: () -> Unit) {
    var query by remember(info) { mutableStateOf(info.seriesName) }
    var results by remember(candidates) { mutableStateOf(candidates) }
    var chosen by remember(info) { mutableStateOf<MalAnime?>(null) }
    var episode by remember(info) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val season = info.season?.let { "season $it " }.orEmpty()
    Text("Which MyAnimeList entry is ${info.seriesName} ${season}episode ${info.episode ?: "?"}?", modifier = Modifier.testTag("mal_pick_title"))
    val pick = chosen
    if (pick != null) {
        Text(pick.title, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            episode, { episode = it.filter(Char::isDigit).take(4) },
            label = { Text("Which of its episodes" + if (pick.numEpisodes > 0) " (1–${pick.numEpisodes})" else "") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("mal_pick_episode"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                { episode.toIntOrNull()?.let { container.mal.choose(info, pick, it); onChanged() } },
                enabled = episode.toIntOrNull()?.let { it >= 1 && (pick.numEpisodes == 0 || it <= pick.numEpisodes) } == true,
                modifier = Modifier.testTag("mal_pick_confirm"),
            ) { Text("That's it") }
            TextButton({ chosen = null }) { Text("Back") }
        }
        return
    }
    fun search() {
        scope.launch { results = container.mal.search(query).ifEmpty { results } }
    }
    OutlinedTextField(
        query, { query = it.take(64) },
        label = { Text("Search MyAnimeList") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { search() }),
        trailingIcon = { IconButton(::search) { Icon(Icons.Filled.Search, "Search") } },
        modifier = Modifier.fillMaxWidth().testTag("mal_search"),
    )
    results.forEach { anime ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                chosen = anime
                val e = info.episode ?: 1
                episode = (if (anime.numEpisodes == 0 || e <= anime.numEpisodes) e else 1).toString()
            }.padding(vertical = 6.dp).testTag("mal_candidate_${anime.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(anime, Modifier.width(40.dp).height(56.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(anime.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(anime.mediaType?.uppercase(), anime.year?.toString(), anime.numEpisodes.takeIf { it > 0 }?.let { "$it episodes" }).joinToString(" · "),
                    color = AppColors.TextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
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

@Composable
private fun Choice(label: String, value: String, options: List<String>, modifier: Modifier, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text(if (value.isEmpty()) label else "$label: $value", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, option -> DropdownMenuItem({ Text(option) }, { open = false; onPick(i) }) }
        }
    }
}

/**
 * A search for any show on MyAnimeList: each one found shows where it is on your list, and (signed in) can be added
 * to it, or have its status, score and episodes watched changed right there.
 */
@Composable
fun MalShowSearch(model: MalSearchModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            model.query, { model.query = it.take(64) },
            label = { Text("Search MyAnimeList") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { model.search() }),
            trailingIcon = { IconButton(model::search, Modifier.testTag("mal_find_go")) { Icon(Icons.Filled.Search, "Search") } },
            modifier = Modifier.fillMaxWidth().testTag("mal_find"),
        )
        when {
            model.busy -> CircularProgressIndicator(Modifier.size(28.dp))
            model.error != null -> Text(model.error!!, color = AppColors.Error)
            model.results?.isEmpty() == true -> Text("No shows found.", color = AppColors.TextDim)
            else -> model.results?.forEach { anime -> MalFoundRow(model, anime) }
        }
    }
}

/** The player's MyAnimeList tab, under the playing show: a search for any other (signed in: it's for your list). */
@Composable
fun MalFindSection(container: AppContainer, modifier: Modifier = Modifier) {
    val settings by container.settings.collectAsStateWithLifecycle()
    if (!container.mal.client.configured || settings.mal == null) return
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("mal_find_section"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Find a show", style = MaterialTheme.typography.titleMedium)
        MalShowSearch(rememberMalSearchModel(container))
    }
}

/** One show found: what it is and where it is on your list, with its status, score and episodes to change. */
@Composable
private fun MalFoundRow(model: MalSearchModel, anime: MalAnime) {
    val uriHandler = LocalUriHandler.current
    val list = anime.myListStatus
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("mal_found_${anime.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(anime, Modifier.width(40.dp).height(56.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(anime.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(anime.details(), color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
                if (model.canChange) {
                    Text(
                        anime.listSummary(),
                        color = if (list != null) AppColors.Accent else AppColors.TextDim,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("mal_found_list_${anime.id}"),
                    )
                }
            }
            Box {
                IconButton({ menu = true }, Modifier.testTag("mal_found_more_${anime.id}")) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    if (list != null && model.canChange) {
                        DropdownMenuItem({ Text("Remove from your list") }, { menu = false; model.remove(anime) }, modifier = Modifier.testTag("mal_found_remove_${anime.id}"))
                    }
                    DropdownMenuItem({ Text("Open on MyAnimeList") }, { menu = false; uriHandler.openUri(anime.url) })
                }
            }
        }
        if (!model.canChange) return@Column
        val statuses = MalStatus.entries
        if (list == null) {
            Choice("Add to your list", "", statuses.map { it.label }, Modifier.padding(start = 50.dp).testTag("mal_found_add_${anime.id}")) { i ->
                model.update(anime, MalListUpdate(status = statuses[i]))
            }
            return@Column
        }
        Row(Modifier.padding(start = 50.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice("Status", list.statusValue?.label ?: "Not set", statuses.map { it.label }, Modifier.weight(1f).testTag("mal_found_status_${anime.id}")) { i ->
                model.update(anime, MalListUpdate(status = statuses[i]))
            }
            Choice(
                "Score", list.score.takeIf { it > 0 }?.let { "$it/10" } ?: "Not rated", (10 downTo 1).map { "$it" } + "No score",
                Modifier.weight(1f).testTag("mal_found_score_${anime.id}"),
            ) { i -> model.update(anime, MalListUpdate(score = if (i == 10) 0 else 10 - i)) }
        }
        Row(Modifier.padding(start = 50.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Episodes watched", Modifier.weight(1f), color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
            IconButton({ model.update(anime, MalListUpdate(watchedEpisodes = (list.watchedEpisodes - 1).coerceAtLeast(0))) }, enabled = list.watchedEpisodes > 0) {
                Icon(Icons.Filled.Remove, "One episode fewer")
            }
            Text("${list.watchedEpisodes}", style = MaterialTheme.typography.titleMedium)
            IconButton(
                { model.watchedOneMore(anime) },
                enabled = anime.numEpisodes == 0 || list.watchedEpisodes < anime.numEpisodes,
                modifier = Modifier.testTag("mal_found_plus_${anime.id}"),
            ) { Icon(Icons.Filled.Add, "One episode more") }
        }
    }
}

/** The player's MyAnimeList message: the update made (a brief note, with Undo), or a question. */
@Composable
fun MalNoticeCard(container: AppContainer, onPick: () -> Unit, modifier: Modifier = Modifier) {
    val notice by container.mal.notice.collectAsStateWithLifecycle()
    val n = notice ?: return
    LaunchedEffect(n) {
        kotlinx.coroutines.delay(n.showMillis)
        container.mal.dismissNotice()
    }
    Surface(color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(10.dp), modifier = modifier.widthIn(max = if (n.question) 460.dp else 300.dp).testTag("mal_notice")) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                n.text,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (n.question) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(vertical = if (n.undo == null && !n.question) 8.dp else 0.dp),
            )
            n.undo?.let { undo -> TextButton({ undo(); container.mal.dismissNotice() }, Modifier.testTag("mal_notice_undo")) { Text("Undo") } }
            n.confirm?.let { yes -> TextButton({ yes(); container.mal.dismissNotice() }, Modifier.testTag("mal_notice_yes")) { Text("Yes") } }
            if (n.pick) TextButton({ onPick(); container.mal.dismissNotice() }, Modifier.testTag("mal_notice_pick")) { Text("Pick") }
            if (n.question) TextButton(container.mal::dismissNotice) { Text("✕", color = OnVideo.Text) }
        }
    }
}

/**
 * Everything MyAnimeList in one place (the home card opens it): the account, what's playing, your list (to update
 * by hand), the update settings, the shows you matched or stopped tracking, and updates waiting to be sent. Wide
 * windows put your list beside the rest.
 */
@Composable
fun MobileMalScreen(container: AppContainer, nav: Navigator) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val state by container.mal.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        MobileTopBar("MyAnimeList", nav)
        val account = settings.mal
        if (!container.mal.client.configured || account == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (container.mal.client.configured) MAL_PRIVACY_NOTE else "MyAnimeList isn't set up in this build of YarmiplayTV.", color = AppColors.TextDim)
                if (container.mal.client.configured) Button({ nav.push(Screen.MalLogin) }, Modifier.testTag("mal_hub_sign_in")) { Text("Sign in") }
            }
            return@Column
        }
        val search = rememberMalSearchModel(container)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 900.dp) {
                Row(Modifier.fillMaxSize().testTag("mal_hub_wide")) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        MalAccountCard(container, account.userName, account.picture)
                        MalNowPlaying(container, state, nav)
                        MalHubSettings(container)
                    }
                    Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        MalSection("Find a show") { MalShowSearch(search) }
                        MalYourList(container, search.changes)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MalAccountCard(container, account.userName, account.picture)
                    MalSection("Find a show") { MalShowSearch(search) }
                    MalNowPlaying(container, state, nav)
                    MalYourList(container, search.changes)
                    MalHubSettings(container)
                }
            }
        }
    }
}

@Composable
private fun MalSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(color = AppColors.Surface, contentColor = AppColors.OnSurface, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        SurfaceInk {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
        }
    }
}

@Composable
private fun MalAccountCard(container: AppContainer, userName: String, picture: String?) {
    var confirm by remember { mutableStateOf(false) }
    Surface(color = AppColors.Surface, contentColor = AppColors.OnSurface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        SurfaceInk {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(AppColors.SurfaceHigh)) {
                picture?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(userName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("mal_hub_user"))
                Text(MAL_ATTRIBUTION, color = AppColors.TextDim, style = MaterialTheme.typography.labelSmall)
            }
            TextButton({ confirm = true }, Modifier.testTag("mal_hub_sign_out")) { Text("Sign out") }
        }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Sign out of MyAnimeList?") },
            text = { Text("Everything YarmiplayTV keeps for MyAnimeList on this device is deleted: the sign-in, your matched shows and updates not sent yet.") },
            confirmButton = { TextButton({ confirm = false; container.mal.signOut() }, Modifier.testTag("mal_sign_out")) { Text("Sign out") } },
            dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MalNowPlaying(container: AppContainer, state: MalState, nav: Navigator) {
    if (state == MalState.Idle) return
    Surface(color = AppColors.Surface, contentColor = AppColors.OnSurface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        SurfaceInk {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text("Now playing", style = MaterialTheme.typography.labelLarge, color = AppColors.TextDim, modifier = Modifier.padding(horizontal = 16.dp))
            MalContent(container, state, onSignIn = { nav.push(Screen.MalLogin) })
        }
        }
    }
}

/**
 * Your list by status, newest change first; each entry's episodes and score can be changed right here. It's read
 * again when [changes] (made by the search beside it) goes up.
 */
@Composable
private fun MalYourList(container: AppContainer, changes: Int = 0) {
    val statuses = listOf(MalStatus.WATCHING, MalStatus.PLAN_TO_WATCH, MalStatus.COMPLETED, MalStatus.ON_HOLD, MalStatus.DROPPED)
    var status by remember { mutableStateOf(MalStatus.WATCHING) }
    var entries by remember { mutableStateOf<List<Pair<MalAnime, MalListStatus>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(status, reload, changes) {
        entries = null
        error = null
        runCatching { container.mal.myList(status, fresh = reload > 0 || changes > 0) }
            .onSuccess { entries = it }
            .onFailure { error = it.message ?: "Couldn't load your list" }
    }
    MalSection("Your list") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            statuses.forEach { s ->
                FilterChip(status == s, { status = s }, label = { Text(s.label) }, modifier = Modifier.testTag("mal_list_${s.api}"))
            }
        }
        when {
            error != null -> Text(error!!, color = AppColors.Error)
            entries == null -> CircularProgressIndicator(Modifier.size(28.dp))
            entries!!.isEmpty() -> Text("Nothing here.", color = AppColors.TextDim)
            else -> entries!!.forEach { (anime, list) ->
                MalListRow(container, anime, list, lookUp = status in LOOK_UP_EPISODES) { update ->
                    scope.launch {
                        container.mal.update(anime.copy(myListStatus = list), update)
                        reload++
                    }
                }
            }
        }
    }
}

/** Lists whose entries look for their episodes in the library by themselves; the others when Episodes is opened. */
private val LOOK_UP_EPISODES = setOf(MalStatus.WATCHING, MalStatus.PLAN_TO_WATCH, MalStatus.ON_HOLD)

/**
 * One entry of your list: progress, score and status to change, and when the library has the next episode, Play
 * and Add to playlist for it ([lookUp]: find out by itself, else when Episodes is opened). Episodes lists every
 * episode of the entry the library has.
 */
@Composable
private fun MalListRow(container: AppContainer, anime: MalAnime, list: MalListStatus, lookUp: Boolean, onUpdate: (MalListUpdate) -> Unit) {
    val uriHandler = LocalUriHandler.current
    var menu by remember { mutableStateOf(false) }
    var episodes by remember(anime.id) { mutableStateOf<List<AvailableEpisode>?>(null) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(anime.id, lookUp || picking) {
        if ((lookUp || picking) && episodes == null) episodes = container.malEpisodes.of(anime)
    }
    val room by container.sync.room.collectAsStateWithLifecycle()
    val inRoom = room.status == ConnectionStatus.CONNECTED
    val next = episodes?.let { nextEpisode(it, list.watchedEpisodes) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().testTag("mal_list_entry_${anime.id}"), verticalAlignment = Alignment.CenterVertically) {
            Cover(anime, Modifier.width(40.dp).height(56.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(anime.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val of = if (anime.numEpisodes > 0) " of ${anime.numEpisodes}" else ""
                Text(
                    "${list.watchedEpisodes}$of watched" + (if (list.score > 0) " · ${list.score}/10" else "") + (if (list.isRewatching) " · rewatching" else ""),
                    color = AppColors.TextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton({ onUpdate(MalListUpdate(watchedEpisodes = (list.watchedEpisodes - 1).coerceAtLeast(0))) }, enabled = list.watchedEpisodes > 0) {
                Icon(Icons.Filled.Remove, "One episode fewer")
            }
            IconButton(
                { onUpdate(MalService.progressUpdate(list, list.watchedEpisodes + 1, anime.numEpisodes, java.time.LocalDate.now().toString()) ?: MalListUpdate(watchedEpisodes = list.watchedEpisodes + 1)) },
                enabled = anime.numEpisodes == 0 || list.watchedEpisodes < anime.numEpisodes,
                modifier = Modifier.testTag("mal_list_plus_${anime.id}"),
            ) { Icon(Icons.Filled.Add, "One episode more") }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    MalStatus.entries.filter { it != list.statusValue }.forEach { s ->
                        DropdownMenuItem({ Text("Move to ${s.label}") }, { menu = false; onUpdate(MalListUpdate(status = s)) })
                    }
                    (10 downTo 1).forEach { score ->
                        DropdownMenuItem({ Text("Score $score/10") }, { menu = false; onUpdate(MalListUpdate(score = score)) })
                    }
                    DropdownMenuItem({ Text("Episodes…") }, { menu = false; picking = true }, modifier = Modifier.testTag("mal_episodes_${anime.id}"))
                    DropdownMenuItem({ Text("Open on MyAnimeList") }, { menu = false; uriHandler.openUri(anime.url) })
                }
            }
        }
        next?.let { ep ->
            Row(Modifier.padding(start = 50.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton({ playEpisode(container, ep) }, Modifier.testTag("mal_play_next_${anime.id}"), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Episode ${ep.number}")
                }
                if (inRoom) {
                    IconButton({ addEpisodeToPlaylist(container, ep) }, Modifier.testTag("mal_add_next_${anime.id}")) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add episode ${ep.number} to the playlist")
                    }
                }
            }
        }
    }
    if (picking) EpisodesDialog(container, anime, episodes, list.watchedEpisodes, inRoom) { picking = false }
}

/** Every episode of [anime] the library has, to play or add to the playlist; the next one to watch stands out. */
@Composable
private fun EpisodesDialog(container: AppContainer, anime: MalAnime, episodes: List<AvailableEpisode>?, watched: Int, inRoom: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(anime.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            when {
                episodes == null -> CircularProgressIndicator(Modifier.size(28.dp))
                episodes.isEmpty() -> Text("None of its episodes are in your library or media folders.", color = AppColors.TextDim)
                else -> Column(Modifier.verticalScroll(rememberScrollState()).testTag("mal_episodes_list")) {
                    episodes.forEach { ep ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Episode ${ep.number}" + if (ep.number <= watched) " · watched" else "",
                                    color = if (ep.number == watched + 1) AppColors.Accent else AppColors.Text,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(ep.title, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton({ playEpisode(container, ep); onDismiss() }) { Icon(Icons.Filled.PlayArrow, "Play episode ${ep.number}") }
                            if (inRoom) {
                                IconButton({ addEpisodeToPlaylist(container, ep) }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add episode ${ep.number} to the playlist") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

/** The update settings, matched shows, shows not tracked, and updates waiting to be sent. */
@Composable
private fun MalHubSettings(container: AppContainer) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun savePrefs(auto: Boolean = settings.malAutoUpdate, ask: Boolean = settings.malAskFirst) =
        container.scope.launch { container.settingsStore.saveMalPrefs(auto, ask) }
    MalSection("Updating your list") {
        MalSwitchRow("Update my list as I watch", "When an episode is 90% watched, with Undo", settings.malAutoUpdate, "mal_hub_auto") { savePrefs(auto = it) }
        if (settings.malAutoUpdate) MalSwitchRow("Ask before each update", null, settings.malAskFirst, "mal_hub_ask") { savePrefs(ask = it) }
    }
    if (settings.malMappings.isNotEmpty()) {
        MalSection("Shows you matched") {
            val titles = remember { mutableStateMapOf<Int, String>() }
            LaunchedEffect(settings.malMappings) {
                settings.malMappings.values.map { it.malId }.distinct().filter { it !in titles }.forEach { id ->
                    runCatching { container.mal.anime(id) }.onSuccess { titles[id] = it.title }
                }
            }
            settings.malMappings.forEach { (key, mapping) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val show = key.substringBefore('|').substringAfterLast('/')
                    val season = key.substringAfter('|').toIntOrNull()?.takeIf { it > 0 }?.let { " · season $it" }.orEmpty()
                    Column(Modifier.weight(1f)) {
                        Text(titles[mapping.malId] ?: "MyAnimeList #${mapping.malId}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Your $show$season", color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton({ container.scope.launch { container.settingsStore.saveMalMapping(key, null) } }) { Text("Forget") }
                }
            }
            TextButton({ container.scope.launch { container.settingsStore.clearMalMappings() } }) { Text("Forget all") }
        }
    }
    if (settings.malIgnored.isNotEmpty()) {
        MalSection("Not tracked") {
            settings.malIgnored.forEach { key ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(settings.malIgnoredNames[key]?.ifBlank { null } ?: key.substringAfterLast('/'), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton({ container.mal.ignore(key, false) }) { Text("Track again") }
                }
            }
        }
    }
    if (settings.malQueue.isNotEmpty()) {
        MalSection("Waiting to be sent") {
            Text("${settings.malQueue.size} update${if (settings.malQueue.size == 1) "" else "s"} couldn't reach MyAnimeList yet; they're sent again by themselves.", color = AppColors.TextDim)
            OutlinedButton({ scope.launch { container.mal.flushQueue() } }, Modifier.testTag("mal_hub_send_now")) { Text("Send now") }
        }
    }
}

@Composable
private fun MalSwitchRow(title: String, subtitle: String?, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked, onChange, Modifier.testTag(tag))
    }
}
