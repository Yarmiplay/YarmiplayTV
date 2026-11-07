package com.yarmiplaytv.update

/**
 * A desktop update that stopped, with the stage that stopped and whatever the system actually reported.
 * [detail] is that report (an HTTP status, a checksum, an installer message, an exit code line). It is not a guess.
 */
data class UpdateFailure(
    val version: String?,
    val stage: String,
    val exitCode: Int?,
    val detail: String?,
)

/** Thrown when a step of an update fails. [message] is the sentence the notice shows. */
class UpdateFailedException(
    val stage: String,
    val detail: String?,
    val exitCode: Int? = null,
    cause: Throwable? = null,
) : Exception(explainUpdateFailure(stage, exitCode, detail), cause)

/** The sentence in front of a failure, naming the step. Unknown stages stay with the installer. */
fun updateStageLead(stage: String): String = when (stage) {
    "check" -> "Couldn't check for updates"
    "download" -> "Couldn't download the update"
    "validate" -> "Couldn't verify the download"
    "unpack" -> "Couldn't unpack the update"
    "launch" -> "Couldn't start the installer"
    "replace" -> "Couldn't replace the app's files"
    "restart" -> "The update installed, but YarmiplayTV didn't start again"
    else -> "The installer didn't finish"
}

/**
 * One readable sentence: the step, then the exit code and [detail] when we have them.
 * A bare exit code stays a bare exit code. [detail] is redacted before it is shown.
 */
fun explainUpdateFailure(stage: String, exitCode: Int?, detail: String?): String {
    val clean = detail?.let(::redactUpdateText)?.takeIf { it.isNotEmpty() }
    val lead = updateStageLead(stage)
    if (clean != null && (clean.startsWith(lead) || clean.startsWith(lead.removeSuffix(".")))) return clean
    val exitOnly = exitCode?.let { "exit code $it" }
    if (clean != null && exitOnly != null && clean.equals(exitOnly, ignoreCase = true)) return "$lead ($exitOnly)."
    return when {
        clean == null && exitOnly == null -> "$lead."
        clean == null -> "$lead ($exitOnly)."
        exitOnly != null -> "$lead ($exitOnly): $clean"
        else -> "$lead: $clean"
    }
}

/**
 * Drops credentials that sometimes ride along in URLs and exception text: query strings, fragments,
 * and `token` / `password` / `Bearer` assignments. Paths and ordinary error text stay.
 */
fun redactUpdateText(raw: String): String {
    var text = raw.replace(Regex("[\\r\\n\\t]+"), " ").trim()
    text = text.replace(Regex("(?i)(https?://[^\\s?#]+)\\?[^\\s#]*"), "$1")
    text = text.replace(Regex("(?i)(https?://[^\\s?#]+)#\\S*"), "$1")
    text = text.replace(Regex("(?i)\\bbearer\\s+[A-Za-z0-9\\-._~+/]+=*"), "Bearer (redacted)")
    text = text.replace(
        Regex("(?i)\\b(authorization|token|password|passwd|secret|sig|signature|access_token|refresh_token|api_key|apikey)\\b\\s*[:=]\\s*\\S+"),
        "$1=(redacted)",
    )
    return text.take(500)
}

/** Pipe form carried in [com.yarmiplaytv.desktop] `YARMIPLAYTV_UPDATE_FAILED`, including the older `version|exit=|detail`. */
fun formatUpdateFailure(failure: UpdateFailure): String {
    val version = failure.version?.trim().orEmpty()
    val stage = failure.stage.ifBlank { "install" }
    val detail = failure.detail?.let(::redactUpdateText)?.takeIf { it.isNotEmpty() }
    if (version.isEmpty()) {
        return buildString {
            append("stage=").append(stage).append('\n')
            append("exit=").append(failure.exitCode?.toString().orEmpty()).append('\n')
            append("detail=").append(detail.orEmpty()).append('\n')
        }
    }
    val pipeDetail = detail?.replace("|", "/")
    return buildString {
        append(version)
        append("|stage=")
        append(stage)
        append("|exit=")
        append(failure.exitCode ?: "")
        if (pipeDetail != null) {
            append('|')
            append(pipeDetail)
        }
    }
}

/**
 * Reads either the pipe form (`2.9.8|stage=replace|exit=5|Access is denied`, or the older
 * `2.9.8|exit=5|Access is denied` and `2.9.8`) or the line form written by the helper
 * (`version=`, `stage=`, `exit=`, `detail=`).
 */
fun parseUpdateFailure(raw: String?): UpdateFailure? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if ('\n' in text || text.startsWith("version=") || text.startsWith("stage=") || text.startsWith("\uFEFF")) {
        return parseUpdateFailureFile(text)
    }
    val parts = text.split('|')
    val version = parts[0].trim().ifEmpty { return null }
    var stage: String? = null
    var exit: Int? = null
    val details = mutableListOf<String>()
    for (part in parts.drop(1)) {
        val piece = part.trim()
        when {
            piece.startsWith("stage=") -> stage = piece.removePrefix("stage=").trim().ifEmpty { null }
            piece.startsWith("exit=") -> exit = piece.removePrefix("exit=").trim().toIntOrNull()
            piece.isEmpty() -> Unit
            exit == null && piece.toIntOrNull() != null && !piece.startsWith("stage=") -> exit = piece.toInt()
            else -> details += piece
        }
    }
    return UpdateFailure(version, stage ?: "install", exit, details.joinToString(" | ").ifEmpty { null })
}

/** The helper's `update-failure.txt`. Null when the file has none of the keys. */
fun parseUpdateFailureFile(raw: String): UpdateFailure? {
    var version: String? = null
    var stage: String? = null
    var exit: Int? = null
    var detail: String? = null
    var saw = false
    for (rawLine in raw.removePrefix("\uFEFF").lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty() || '=' !in line) continue
        val key = line.substringBefore('=').trim()
        val value = line.substringAfter('=').trim()
        when (key) {
            "version" -> { version = value.ifEmpty { null }; saw = true }
            "stage" -> { stage = value.ifEmpty { null }; saw = true }
            "exit" -> { exit = value.toIntOrNull(); saw = true }
            "detail" -> { detail = value.ifEmpty { null }; saw = true }
        }
    }
    if (!saw) return null
    return UpdateFailure(version, stage ?: "install", exit, detail)
}

/**
 * Prefers whichever record actually says what happened. A Windows setup status line fills an empty detail.
 * The file wins a tie so a helper that could not pass the environment still beats a version-only signal.
 */
fun mergeUpdateFailures(file: UpdateFailure?, env: UpdateFailure?, statusDetail: String?): UpdateFailure? {
    val status = statusDetail?.let(::redactUpdateText)?.takeIf { it.isNotEmpty() }
    fun withStatus(failure: UpdateFailure?): UpdateFailure? {
        if (failure == null) return null
        if (!failure.detail.isNullOrBlank() || status == null) return failure
        return failure.copy(detail = status)
    }
    val fromFile = withStatus(file)
    val fromEnv = withStatus(env)
    val fromStatus = if (fromFile == null && fromEnv == null && status != null) {
        UpdateFailure(null, "install", null, status)
    } else {
        null
    }
    return listOfNotNull(fromFile, fromEnv, fromStatus).maxByOrNull { failureScore(it) }
}

private fun failureScore(failure: UpdateFailure): Int {
    var score = 0
    if (!failure.detail.isNullOrBlank()) score += 4
    if (failure.exitCode != null) score += 2
    if (failure.stage != "install") score += 1
    if (!failure.version.isNullOrBlank()) score += 1
    return score
}
