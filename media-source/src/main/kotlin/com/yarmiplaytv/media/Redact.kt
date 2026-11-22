package com.yarmiplaytv.media

/** Takes media-server tokens out of text before it is logged: URLs carry them as `api_key=` or `X-Plex-Token=`. */
object Redact {
    private val tokenParams = Regex("""(?i)\b(api_key|apikey|x-plex-token|access_token)=[^&\s"'#<>]+""")

    /** [text] with every token query value replaced by `REDACTED`. */
    fun tokens(text: String?): String = text?.let { tokenParams.replace(it, "$1=REDACTED") }.orEmpty()

    /**
     * [error] itself when no message in its chain holds a token, otherwise a copy of the chain with the tokens taken
     * out of the messages (same types' names and stack traces), for logging.
     */
    fun tokens(error: Throwable): Throwable {
        if (generateSequence(error) { it.cause }.take(MAX_CAUSES).none { tokenParams.containsMatchIn(it.message.orEmpty()) }) return error
        return copy(error, 0)
    }

    private fun copy(error: Throwable, depth: Int): Throwable {
        val cause = error.cause?.takeIf { depth < MAX_CAUSES && it !== error }?.let { copy(it, depth + 1) }
        return RedactedException("${error.javaClass.name}: ${tokens(error.message)}", cause).also { it.stackTrace = error.stackTrace }
    }

    private class RedactedException(message: String, cause: Throwable?) : Exception(message, cause) {
        override fun toString(): String = message.orEmpty()
    }

    private const val MAX_CAUSES = 8
}
