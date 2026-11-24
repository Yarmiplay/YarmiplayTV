package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.ui.browse.openItem
import com.yarmiplaytv.ui.browse.serverNameOf
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** How many titles Home shows from each library before "See all". */
internal const val LIBRARY_PREVIEW_COUNT = 8

internal data class LibrarySection(val library: MediaItem, val items: List<MediaItem>, val error: String?)

/** Every library on [source], with that library's own failure kept separate from the others. */
internal suspend fun loadLibrarySections(source: MediaSource): List<LibrarySection> = coroutineScope {
    source.libraries().map { library ->
        async {
            runCatching { source.children(library) }.fold(
                onSuccess = { LibrarySection(library, it, null) },
                onFailure = { LibrarySection(library, emptyList(), it.message ?: "Couldn't load") },
            )
        }
    }.awaitAll()
}

internal enum class LibrarySort(val label: String) {
    TitleAsc("Title A–Z"),
    TitleDesc("Title Z–A"),
    YearNewest("Year, newest"),
    YearOldest("Year, oldest"),
}

internal fun List<MediaItem>.sortedForLibrary(sort: LibrarySort): List<MediaItem> = when (sort) {
    LibrarySort.TitleAsc -> sortedWith(compareBy { it.name.lowercase() })
    LibrarySort.TitleDesc -> sortedWith(compareByDescending { it.name.lowercase() })
    LibrarySort.YearNewest -> sortedWith(compareByDescending<MediaItem> { it.year ?: Int.MIN_VALUE }.thenBy { it.name.lowercase() })
    LibrarySort.YearOldest -> sortedWith(compareBy<MediaItem> { it.year ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() })
}

/** The library's name, plus its server when two libraries share a name. */
internal fun libraryTitle(library: MediaItem, source: MediaSource, libraries: List<MediaItem>): String {
    val name = library.name.ifBlank { "Library" }
    val server = serverNameOf(source, library)
    val shared = libraries.count { it.name == library.name } > 1
    return if (server != null && shared) "$name · $server" else name
}

internal fun librarySeeAllTag(library: MediaItem) = "see_all_${libraryTag(library)}"

internal fun libraryFilterTag(library: MediaItem) = "library_filter_${libraryTag(library)}"

private fun libraryTag(library: MediaItem) = library.uniqueKey.replace(Regex("[^A-Za-z0-9_]"), "_")

/**
 * The full libraries: every title from the connected servers, narrowed to one library and reordered
 * when there's more than one way to look at them.
 */
@Composable
fun MobileLibraryScreen(container: AppContainer, nav: Navigator, focusKey: String?, kind: DeviceKind) {
    val source by container.mediaSource.collectAsStateWithLifecycle()
    var sections by remember { mutableStateOf<List<LibrarySection>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedKey by rememberSaveable { mutableStateOf(focusKey) }
    var sortName by rememberSaveable { mutableStateOf(LibrarySort.TitleAsc.name) }
    val sort = LibrarySort.entries.firstOrNull { it.name == sortName } ?: LibrarySort.TitleAsc

    LaunchedEffect(focusKey) { selectedKey = focusKey }
    LaunchedEffect(source) {
        val s = source ?: run { sections = emptyList(); loading = false; error = null; return@LaunchedEffect }
        loading = true
        error = null
        runCatching { loadLibrarySections(s) }
            .onSuccess { sections = it }
            .onFailure { error = it.message; sections = emptyList() }
        loading = false
    }

    Column(Modifier.fillMaxSize().testTag("library")) {
        MobileTopBar("Library", nav, showBack = false) {
            TopSearchButton(onClick = { nav.push(Screen.Search()) })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                source == null -> EmptyMessage("Connect Jellyfin or Plex first")
                loading && sections.isEmpty() -> EmptyMessage("Loading…")
                error != null && sections.isEmpty() -> EmptyMessage("Couldn't load libraries: $error")
                sections.isEmpty() -> EmptyMessage("No libraries on this server")
                else -> {
                    val current = source
                    Column(Modifier.fillMaxSize()) {
                        if (current != null) {
                            LibraryControls(sections, current, selectedKey, { selectedKey = it }, sort) { sortName = it.name }
                        }
                        val section = sections.firstOrNull { it.library.uniqueKey == selectedKey }
                        val items = (if (section != null) section.items else sections.flatMap { it.items })
                            .distinctBy { it.uniqueKey }
                            .sortedForLibrary(sort)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                section?.error != null && items.isEmpty() -> EmptyMessage("Couldn't load: ${section.error}")
                                items.isEmpty() -> EmptyMessage("Nothing here")
                                else -> MobileItemGrid(container, items, kind, showServer = section == null) { openItem(container, nav, it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryControls(
    sections: List<LibrarySection>,
    source: MediaSource,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    sort: LibrarySort,
    onSort: (LibrarySort) -> Unit,
) {
    val libraries = sections.map { it.library }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sections.size > 1) {
            LibraryChips(sections, source, libraries, selectedKey, onSelect, Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        LibrarySortButton(sort, onSort)
    }
}

@Composable
private fun LibraryChips(
    sections: List<LibrarySection>,
    source: MediaSource,
    libraries: List<MediaItem>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = selectedKey == null || sections.none { it.library.uniqueKey == selectedKey },
            onClick = { onSelect(null) },
            label = { Text("All") },
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).testTag("library_filter_all"),
        )
        sections.forEach { section ->
            FilterChip(
                selected = selectedKey == section.library.uniqueKey,
                onClick = { onSelect(section.library.uniqueKey) },
                label = {
                    Text(
                        libraryTitle(section.library, source, libraries),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).testTag(libraryFilterTag(section.library)),
            )
        }
    }
}

@Composable
private fun LibrarySortButton(sort: LibrarySort, onSort: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).testTag("library_sort"),
        ) {
            Icon(Icons.Filled.SortByAlpha, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(sort.label, maxLines = 1, softWrap = false)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LibrarySort.entries.forEach { option ->
                DropdownMenuItem(
                    { Text(option.label) },
                    { open = false; onSort(option) },
                    Modifier.testTag("library_sort_${option.name}"),
                )
            }
        }
    }
}
