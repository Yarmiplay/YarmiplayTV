package com.yarmiplaytv.player

/** What the players accept from callers as an address to open, and how addresses are written to logs. */
object MediaUrls {
    /**
     * mpv's own protocols that are not media on a server or a file: they reach into the process (`fd://`,
     * `fdclose://` and FFmpeg's `pipe://` take or close any file descriptor), build media from other addresses
     * (`edl://`, `slice://`, `lavf://`, `av://`) or from inline data (`memory://`, `hex://`). The players build
     * `fdclose://` themselves for a content:// address; none of these may come in from a room playlist, an intent
     * or a search result.
     */
    private val INTERNAL_PROTOCOLS = setOf(
        "fd", "fdclose", "pipe", "edl", "av", "avdevice", "lavf", "memory", "hex", "slice", "mf", "appending", "null",
    )

    /** Handed to mpv in place of an address it must not open: it has no such protocol, so the file fails to open. */
    const val REFUSED_TARGET = "yarmiplay-refused://"

    /** True for an address naming one of mpv's internal protocols (see [INTERNAL_PROTOCOLS]). */
    fun isInternalProtocol(url: String): Boolean {
        val end = url.indexOf("://")
        if (end <= 0) return false
        return url.substring(0, end).trim().lowercase() in INTERNAL_PROTOCOLS
    }

    private val SECRET_PARAMETER = Regex("""(?i)\b(api_key|apikey|x-plex-token|access_token)=[^&#\s"'<>]+""")

    /** [text] with the values of API keys and access tokens in any address it contains replaced by REDACTED. */
    fun redact(text: String): String {
        if ('=' !in text) return text
        return SECRET_PARAMETER.replace(text) { "${it.groupValues[1]}=REDACTED" }
    }
}
