package com.yarmiplaytv.tracking.detect

/** Reads a video's sound for fingerprinting; the platform's own decoder. Every call blocks. */
interface AudioDecoder {
    /** How long [uri] is in seconds; null when it can't be read. */
    fun duration(uri: String): Double?

    /**
     * Decodes [seconds] of [uri]'s sound from [start] into mono [Fingerprinter.SAMPLE_RATE] Hz samples, handed to [sink]
     * a piece at a time, from the audio track in the first of [languages] it has (else its first). Returns the time the
     * sound handed over really starts at (decoders start at the frame before), or null when it can't be decoded. Stops
     * early once [cancelled] says so.
     */
    fun decode(
        uri: String,
        start: Double,
        seconds: Double,
        languages: List<String>,
        sink: (ShortArray, Int) -> Unit,
        cancelled: () -> Boolean,
    ): Double?
}
