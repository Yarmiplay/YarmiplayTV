package com.yarmiplaytv.ui.chat

/** A URL span inside a chat message. [end] is exclusive. [openUrl] is what a click opens. */
data class ChatLink(
    val start: Int,
    val end: Int,
    val openUrl: String,
)

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
