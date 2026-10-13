package com.yarmiplaytv.media

/** A part of an episode that can be skipped: its opening or its ending. */
data class SkipSegment(val kind: Kind, val start: Double, val end: Double) {
    enum class Kind(val label: String) { OPENING("opening"), ENDING("ending") }
}
