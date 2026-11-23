package com.yarmiplaytv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer

/**
 * Whether icon-only buttons (the player bar, the menu tabs, the playlist tools) show a short name under the icon.
 * Settings > Appearance > Labels under icons; on unless turned off.
 */
val LocalIconLabels = staticCompositionLocalOf { true }

/** The Labels under icons setting, for a theme to provide as [LocalIconLabels]. */
@Composable
fun appIconLabels(container: AppContainer): Boolean {
    val settings by container.settings.collectAsStateWithLifecycle()
    return settings.iconLabels
}

/** What Settings says about the labels switch. */
const val ICON_LABELS_TITLE = "Labels under icons"
const val ICON_LABELS_NOTE = "A short name under the player's buttons, the menu tabs and the playlist tools. Off shows only the icons."

/** The switch for the codec line under the seek bar. */
const val PLAYBACK_DETAILS_TITLE = "Show playback details"
const val PLAYBACK_DETAILS_NOTE = "Under the seek bar: the video's codec, height and hardware or software decoding"
