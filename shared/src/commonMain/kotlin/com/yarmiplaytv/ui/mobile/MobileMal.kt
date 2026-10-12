package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
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
import com.yarmiplaytv.media.mal.MalListUpdate
import com.yarmiplaytv.media.mal.MalStatus
import com.yarmiplaytv.tracking.MalState
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.shared.MAL_ATTRIBUTION
import com.yarmiplaytv.ui.shared.MAL_PRIVACY_NOTE
import com.yarmiplaytv.ui.shared.PRIVACY_POLICY_URL
import com.yarmiplaytv.ui.shared.malInfo
import com.yarmiplaytv.ui.shared.rememberMalLoginModel
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.launch

/** Signing in to MyAnimeList: what's shared and kept first, then MyAnimeList's own page in the browser. */
@Composable
fun MobileMalLoginScreen(container: AppContainer, nav: Navigator) {
    val model = rememberMalLoginModel(container)
    val uriHandler = LocalUriHandler.current
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
                    { model.signInWithBrowser(uriHandler::openUri) { nav.back() } },
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
        TextButton({ mal.ignore(state.info.seriesKey, true); onChanged() }, Modifier.testTag("mal_ignore")) { Text("Don't track this show") }
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
        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text("$label: $value", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, option -> DropdownMenuItem({ Text(option) }, { open = false; onPick(i) }) }
        }
    }
}

/** The player's MyAnimeList message: the update made (with Undo), or a question. */
@Composable
fun MalNoticeCard(container: AppContainer, onPick: () -> Unit, modifier: Modifier = Modifier) {
    val notice by container.mal.notice.collectAsStateWithLifecycle()
    val n = notice ?: return
    LaunchedEffect(n) {
        kotlinx.coroutines.delay(if (n.confirm != null || n.pick) 30_000 else 10_000)
        container.mal.dismissNotice()
    }
    Surface(color = Color.Black.copy(alpha = 0.75f), shape = RoundedCornerShape(12.dp), modifier = modifier.widthIn(max = 460.dp).testTag("mal_notice")) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(n.text, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            n.undo?.let { undo -> TextButton({ undo(); container.mal.dismissNotice() }, Modifier.testTag("mal_notice_undo")) { Text("Undo") } }
            n.confirm?.let { yes -> TextButton({ yes(); container.mal.dismissNotice() }, Modifier.testTag("mal_notice_yes")) { Text("Yes") } }
            if (n.pick) TextButton({ onPick(); container.mal.dismissNotice() }, Modifier.testTag("mal_notice_pick")) { Text("Pick") }
            TextButton(container.mal::dismissNotice) { Text("✕", color = AppColors.TextDim) }
        }
    }
}
