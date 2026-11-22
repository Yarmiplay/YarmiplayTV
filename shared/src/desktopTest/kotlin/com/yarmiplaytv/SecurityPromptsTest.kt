package com.yarmiplaytv

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SecurityPromptsTest {
    private val prompts = SecurityPrompts()
    private val question = SecurityQuestions.untrustedCertificate("media.example")

    private suspend fun shown(): SecurityPrompts.Pending = withTimeout(5_000) {
        while (prompts.current.value == null) delay(5)
        prompts.current.value!!
    }

    @Test
    fun `a yes is remembered for the rest of the run`() = runBlocking {
        val first = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("certificate:media.example", question) }
        prompts.answer(shown(), SecurityAnswer.CONFIRM)
        assertEquals(SecurityAnswer.CONFIRM, first.await())
        assertNull(prompts.current.value)
        // Asked again: answered at once, nothing shown.
        assertEquals(SecurityAnswer.CONFIRM, prompts.ask("certificate:media.example", question))
        assertNull(prompts.current.value)
    }

    @Test
    fun `never ask counts as yes from then on`() = runBlocking {
        val first = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("syncplay:a:8999", SecurityQuestions.syncplayPlainText("a")) }
        prompts.answer(shown(), SecurityAnswer.NEVER_ASK)
        assertEquals(SecurityAnswer.NEVER_ASK, first.await())
        assertEquals(SecurityAnswer.CONFIRM, prompts.remembered("syncplay:a:8999"))
    }

    @Test
    fun `cancel is asked again unless it was to be remembered`() = runBlocking {
        val first = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("k", question) }
        prompts.answer(shown(), SecurityAnswer.CANCEL)
        assertEquals(SecurityAnswer.CANCEL, first.await())
        assertNull(prompts.remembered("k"))

        val background = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("jellyfin:a:8920", question, rememberCancel = true) }
        prompts.answer(shown(), SecurityAnswer.CANCEL)
        assertEquals(SecurityAnswer.CANCEL, background.await())
        assertEquals(SecurityAnswer.CANCEL, prompts.ask("jellyfin:a:8920", question, rememberCancel = true))
        assertNull(prompts.current.value)

        // A sign-in the user starts asks again.
        val signIn = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("jellyfin:a:8920", question, rememberCancel = true, reuseCancel = false) }
        prompts.answer(shown(), SecurityAnswer.CONFIRM)
        assertEquals(SecurityAnswer.CONFIRM, signIn.await())
        assertEquals(SecurityAnswer.CONFIRM, prompts.ask("jellyfin:a:8920", question, rememberCancel = true))
    }

    @Test
    fun `the same question from two places is shown once, other questions wait their turn`() = runBlocking {
        val a1 = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("a", question) }
        val a2 = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("a", question) }
        val b = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("b", SecurityQuestions.jellyfinPlainHttp("b")) }
        val first = shown()
        assertEquals("a", first.key)
        prompts.answer(first, SecurityAnswer.CONFIRM)
        assertEquals(SecurityAnswer.CONFIRM, a1.await())
        assertEquals(SecurityAnswer.CONFIRM, a2.await())
        val second = shown()
        assertEquals("b", second.key)
        prompts.answer(second, SecurityAnswer.CANCEL)
        assertEquals(SecurityAnswer.CANCEL, b.await())
        assertNull(prompts.current.value)
    }

    @Test
    fun `a question nobody waits for any more goes away`() = runBlocking {
        val asking = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("gone", question) }
        val pending = shown()
        asking.cancel()
        withTimeout(5_000) { while (prompts.current.value != null) delay(5) }
        // A late answer to it changes nothing.
        prompts.answer(pending, SecurityAnswer.CONFIRM)
        assertNull(prompts.remembered("gone"))
    }

    @Test
    fun `the wording`() {
        val jellyfin = SecurityQuestions.jellyfinPlainHttp("jf.example:8920")
        assertEquals("Connect without encryption?", jellyfin.title)
        assertEquals(
            "jf.example:8920 didn't answer over HTTPS. Connect to it over plain HTTP? Your Jellyfin password and sign-in would travel unencrypted on this network.",
            jellyfin.text,
        )
        assertEquals("Connect without encryption", jellyfin.confirm)
        assertNull(jellyfin.neverAsk)
        val syncplay = SecurityQuestions.syncplayPlainText("syncplay.pl")
        assertEquals(
            "syncplay.pl didn't set up an encrypted connection. Connect anyway? The room, chat and your server password's hash would travel unencrypted.",
            syncplay.text,
        )
        assertEquals(listOf("Connect", "Cancel", "Never ask"), listOf(syncplay.confirm, syncplay.cancel, syncplay.neverAsk))
        val certificate = SecurityQuestions.untrustedCertificate("media.example")
        assertEquals("Can't check this server's certificate", certificate.title)
        assertEquals("media.example's certificate can't be verified, so this connection may not be private. Play anyway?", certificate.text)
        assertEquals("Play anyway", certificate.confirm)
        assertSame(null, certificate.neverAsk)
    }
}
