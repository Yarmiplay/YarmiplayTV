package com.yarmiplaytv.desktop

import com.yarmiplaytv.local.FileLocalLibrary
import com.yarmiplaytv.player.desktop.MpvAudioGrabber
import com.yarmiplaytv.tracking.detect.AudioDecoder
import com.yarmiplaytv.tracking.detect.Fingerprinter

/** Videos' sound for listening for openings and endings, read by mpv: files on this computer and servers' streams. */
internal object MpvAudioDecoder : AudioDecoder {
    override fun duration(uri: String): Double? = MpvAudioGrabber.duration(pathOf(uri))

    override fun decode(
        uri: String,
        start: Double,
        seconds: Double,
        languages: List<String>,
        sink: (ShortArray, Int) -> Unit,
        cancelled: () -> Boolean,
    ): Double? =
        start.takeIf { MpvAudioGrabber.grab(pathOf(uri), start, seconds, Fingerprinter.SAMPLE_RATE, languages.joinToString(","), sink, cancelled) }

    private fun pathOf(uri: String): String = if (uri.startsWith("file:")) FileLocalLibrary.pathOf(uri).toString() else uri
}
