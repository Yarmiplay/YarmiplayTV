package com.yarmiplaytv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the user picked: the risky way, not now, or the risky way from now on (where a question offers it). */
enum class SecurityAnswer { CONFIRM, CANCEL, NEVER_ASK }

/** A yes/no question about a connection that would be less private. */
data class SecurityQuestion(
    val title: String,
    val text: String,
    val confirm: String,
    val cancel: String = "Cancel",
    /** A third button that answers yes and stops asking (it turns a setting on); null leaves it out. */
    val neverAsk: String? = null,
)

/**
 * Questions the app asks before a connection that isn't private: one is shown at a time, by the phone, tablet and
 * desktop root UI and by the TV's. Answers are kept per [key][ask] (usually a host) until the app quits, so the user
 * isn't asked again on every request or reconnect.
 */
class SecurityPrompts {
    /** One question on screen; [answer] it. */
    class Pending internal constructor(val key: String, val question: SecurityQuestion, internal val rememberCancel: Boolean) {
        internal val answer = CompletableDeferred<SecurityAnswer>()
        internal var waiting = 0
    }

    private val lock = Any()
    private val queue = ArrayList<Pending>()
    private val remembered = HashMap<String, SecurityAnswer>()
    private val _current = MutableStateFlow<Pending?>(null)

    /** The question to show now, or null. */
    val current: StateFlow<Pending?> = _current.asStateFlow()

    /** This run's answer for [key], if there is one. */
    fun remembered(key: String): SecurityAnswer? = synchronized(lock) { remembered[key] }

    /**
     * Asks [question] unless [key] was answered before in this run: yes (or "never ask") always counts again,
     * Cancel only when [rememberCancel] (for background checks; a sign-in the user starts asks again). The same
     * question asked from several places at once is shown once. Cancelling the caller takes the question away.
     */
    suspend fun ask(key: String, question: SecurityQuestion, rememberCancel: Boolean = false, reuseCancel: Boolean = true): SecurityAnswer {
        val pending = synchronized(lock) {
            when (val before = remembered[key]) {
                null -> Unit
                SecurityAnswer.CANCEL -> if (reuseCancel) return before
                else -> return before
            }
            (queue.firstOrNull { it.key == key } ?: Pending(key, question, rememberCancel).also { queue += it; publish() })
                .also { it.waiting++ }
        }
        try {
            return pending.answer.await()
        } finally {
            synchronized(lock) {
                // The last caller gave up before anyone answered: nobody is waiting for it any more.
                if (--pending.waiting == 0 && !pending.answer.isCompleted && queue.remove(pending)) publish()
            }
        }
    }

    /** The user's answer to [pending] (dismissing the dialog is [SecurityAnswer.CANCEL]). */
    fun answer(pending: Pending, answer: SecurityAnswer) {
        synchronized(lock) {
            if (!queue.remove(pending)) return
            when (answer) {
                SecurityAnswer.CONFIRM, SecurityAnswer.NEVER_ASK -> remembered[pending.key] = SecurityAnswer.CONFIRM
                SecurityAnswer.CANCEL -> if (pending.rememberCancel) remembered[pending.key] = SecurityAnswer.CANCEL
            }
            publish()
        }
        pending.answer.complete(answer)
    }

    private fun publish() {
        _current.value = queue.firstOrNull()
    }
}

/** The wording of each question. */
object SecurityQuestions {
    fun jellyfinPlainHttp(server: String) = SecurityQuestion(
        title = "Connect without encryption?",
        text = "$server didn't answer over HTTPS. Connect to it over plain HTTP? Your Jellyfin password and sign-in " +
            "would travel unencrypted on this network.",
        confirm = "Connect without encryption",
    )

    fun syncplayPlainText(host: String) = SecurityQuestion(
        title = "Connect without encryption?",
        text = "$host didn't set up an encrypted connection. Connect anyway? The room, chat and your server " +
            "password's hash would travel unencrypted.",
        confirm = "Connect",
        neverAsk = "Never ask",
    )

    fun untrustedCertificate(host: String) = SecurityQuestion(
        title = "Can't check this server's certificate",
        text = "$host's certificate can't be verified, so this connection may not be private. Play anyway?",
        confirm = "Play anyway",
    )
}
