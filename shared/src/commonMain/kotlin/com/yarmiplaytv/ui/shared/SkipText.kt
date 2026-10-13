package com.yarmiplaytv.ui.shared

import com.yarmiplaytv.sync.RoomSkip

/** Skipping anime openings and endings: the wording the phone, desktop and TV settings and room screens share. */
const val SKIP_ALONE_TITLE = "Skip openings and endings when watching alone"
const val SKIP_ALONE_SUBTITLE =
    "Found from the file's chapters, your media server's markers or, for anime, AniSkip. In a room, the room's setting decides"
const val SKIP_BUTTON_TITLE = "Show a Skip button at openings and endings"
const val SKIP_BUTTON_SUBTITLE = "For 3 seconds as one starts, when it isn't skipped automatically"

const val ROOM_SKIP_TITLE = "Skip openings and endings"

/** Under the room's toggle: whose setting it is and who can change it. */
fun roomSkipSubtitle(skip: RoomSkip): String = when {
    !skip.shared -> "Your choice on this server: when you skip, the room jumps with you"
    !skip.canChange -> "For everyone in the room · only room operators can change it"
    skip.setBy != null -> "For everyone in the room · turned ${if (skip.on) "on" else "off"} by ${skip.setBy}"
    else -> "For everyone in the room"
}
