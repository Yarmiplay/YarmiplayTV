package com.yarmiplaytv.ui.chat

/** A URL span inside a chat message. [end] is exclusive. [openUrl] is what a click opens. */
data class ChatLink(
    val start: Int,
    val end: Int,
    val openUrl: String,
)

/** A playback timestamp span inside a chat message (e.g. `14:22`, `1:05:10`, `@0:45`). [end] is exclusive. */
data class ChatTimestamp(
    val start: Int,
    val end: Int,
    val seconds: Int,
)

/** A clickable span inside a chat message body: either a URL or a playback timestamp. */
sealed interface ChatSpan {
    val start: Int
    val end: Int

    data class Url(override val start: Int, override val end: Int, val openUrl: String) : ChatSpan
    data class Timestamp(override val start: Int, override val end: Int, val seconds: Int) : ChatSpan
}

/**
 * Finds playback timestamps (`M:SS`, `MM:SS`, `H:MM:SS`, optionally prefixed with `@`) in chat text.
 * Skips any range overlapping [exclude] (URLs always win) and rejects out-of-range fields or tokens embedded
 * inside larger identifiers/dates/IPs.
 */
object ChatTimestamps {
    private val finder = Regex("""(?<![A-Za-z0-9:/._-])@?(?:(\d{1,2}):)?(\d{1,3}):([0-5]\d)(?![A-Za-z0-9:/._-])""")

    fun find(text: String, exclude: List<ChatLink> = emptyList()): List<ChatTimestamp> {
        if (text.isEmpty() || ':' !in text) return emptyList()
        val out = ArrayList<ChatTimestamp>()
        for (match in finder.findAll(text)) {
            val start = match.range.first
            val end = match.range.last + 1
            if (exclude.any { start < it.end && end > it.start }) continue
            val hours = match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull()
            val minutes = match.groupValues[2].toIntOrNull() ?: continue
            val seconds = match.groupValues[3].toIntOrNull() ?: continue
            if (hours != null && (hours !in 0..99 || minutes !in 0..59)) continue
            if (hours == null && minutes !in 0..599) continue
            val total = (hours ?: 0) * 3600 + minutes * 60 + seconds
            out += ChatTimestamp(start, end, total)
        }
        return out
    }
}

/** Finds non-overlapping URL and timestamp spans in [text], sorted by position. */
object ChatSpans {
    fun find(text: String, includeLinks: Boolean = true, includeTimestamps: Boolean = true): List<ChatSpan> {
        if (text.isEmpty() || (!includeLinks && !includeTimestamps)) return emptyList()
        val links = ChatLinks.find(text)
        val out = ArrayList<ChatSpan>()
        if (includeLinks) {
            links.forEach { out += ChatSpan.Url(it.start, it.end, it.openUrl) }
        }
        if (includeTimestamps) {
            ChatTimestamps.find(text, exclude = links).forEach { out += ChatSpan.Timestamp(it.start, it.end, it.seconds) }
        }
        out.sortBy { it.start }
        return out
    }
}

/** Formats [seconds] as `M:SS` or `H:MM:SS` for chat messages (e.g. `14:22` or `1:05:10`). */
fun formatChatTimestamp(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Finds http(s) and www. links in chat text.
 * The sender name is not scanned. Trailing punctuation stays outside the link,
 * except a closing parenthesis that balances one opened inside the URL.
 */
object ChatLinks {
    private val finder = Regex("""(?i)(?<![A-Za-z0-9@/])(?:https?://|www\.)\S+""")
    private val trailing = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"', '*')

    fun find(text: String): List<ChatLink> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<ChatLink>()
        for (match in finder.findAll(text)) {
            val trimmed = trimTrailing(match.value)
            val open = openUrl(trimmed) ?: continue
            val start = match.range.first
            out += ChatLink(start, start + trimmed.length, open)
        }
        return out
    }

    private fun trimTrailing(raw: String): String {
        var end = raw.length
        while (end > 0 && raw[end - 1] in trailing) {
            if (raw[end - 1] == ')') {
                val slice = raw.substring(0, end)
                if (slice.count { it == '(' } >= slice.count { it == ')' }) break
            }
            end--
        }
        return raw.substring(0, end)
    }

    private fun openUrl(trimmed: String): String? {
        val lower = trimmed.lowercase()
        val schemeLen = when {
            lower.startsWith("https://") -> "https://".length
            lower.startsWith("http://") -> "http://".length
            lower.startsWith("www.") -> "www.".length
            else -> return null
        }
        val rest = trimmed.substring(schemeLen)
        if (rest.isEmpty() || rest.all { it == '.' || it == '/' }) return null
        return if (lower.startsWith("www.")) "https://$trimmed" else trimmed
    }
}
