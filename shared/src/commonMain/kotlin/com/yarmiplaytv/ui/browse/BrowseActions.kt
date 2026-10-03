package com.yarmiplaytv.ui.browse

import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.media.CompositeMediaSource
import com.yarmiplaytv.media.MediaItem
import com.yarmiplaytv.media.MediaItemType
import com.yarmiplaytv.media.MediaSource
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.nav.Screen

fun openItem(container: AppContainer, nav: Navigator, item: MediaItem) {
    if (item.isPlayable) nav.actionItem = item else nav.push(Screen.Browse(item))
}

fun aspectFor(items: List<MediaItem>): Float {
    val posters = items.count { it.type in setOf(MediaItemType.SERIES, MediaItemType.MOVIE, MediaItemType.SEASON) }
    return if (posters > items.size / 2) 2f / 3f else 16f / 9f
}

/** [server] is the item's server name, given where items from several servers are mixed (search). */
fun subtitleFor(item: MediaItem, server: String? = null): String? {
    val detail = when (item.type) {
        MediaItemType.EPISODE -> listOfNotNull(item.seriesName, item.durationSeconds?.let { "${(it / 60).toInt()} min" }).joinToString(" · ")
        MediaItemType.MOVIE -> item.year?.toString()
        MediaItemType.SERIES -> item.year?.toString()
        MediaItemType.VIDEO -> item.fileName
        else -> null
    }
    if (server == null) return detail
    return listOfNotNull(detail?.takeIf { it.isNotEmpty() }, server).joinToString(" · ")
}

/** The name of [item]'s server when [source] browses several servers, else null. */
fun serverNameOf(source: MediaSource?, item: MediaItem): String? = (source as? CompositeMediaSource)?.sourceOf(item)?.displayName
