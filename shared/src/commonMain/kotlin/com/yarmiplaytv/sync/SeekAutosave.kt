package com.yarmiplaytv.sync

/**
 * How often a playing file's resume point is written.
 *
 * mpv reports the playback position about once per video frame. Writing that to disk on every
 * report rewrites the settings file dozens of times a second and makes every settings reader
 * parse it again. Steady playback is saved at [intervalMs]. A new file, a pause after the
 * picture has moved, the end of the file, and a jump (a seek) are saved at once.
 */
internal class SeekAutosave(
    private val intervalMs: Long = 5_000,
    private val jumpSeconds: Double = 8.0,
) {
    private var name: String? = null
    private var position = 0.0
    private var atMs = Long.MIN_VALUE

    fun shouldSave(fileName: String, position: Double, paused: Boolean, ended: Boolean, nowMs: Long): Boolean {
        if (fileName.isBlank() || position <= 1.0) return false
        val switched = fileName != name
        val delta = kotlin.math.abs(position - this.position)
        val due = nowMs - atMs >= intervalMs
        val jump = delta >= jumpSeconds
        val pause = paused && delta >= 0.5
        val end = ended && delta >= 0.05
        if (switched || jump || end || pause || (due && delta >= 0.5)) {
            name = fileName
            this.position = position
            atMs = nowMs
            return true
        }
        return false
    }
}
