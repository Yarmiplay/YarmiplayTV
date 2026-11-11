package com.yarmiplaytv.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLinksTest {
    @Test
    fun findsHttpHttpsAndWwwAndLeavesTrailingPunctuation() {
        val links = ChatLinks.find("see https://example.com/a. and www.example.com please")
        assertEquals(2, links.size)
        assertEquals("https://example.com/a", links[0].openUrl)
        assertEquals("https://example.com/a", "see https://example.com/a. and www.example.com please".substring(links[0].start, links[0].end))
        assertEquals("https://www.example.com", links[1].openUrl)
    }

    @Test
    fun keepsABalancedParenthesisAndDropsAWrappingOne() {
        val wiki = "https://en.wikipedia.org/wiki/Foo_(bar)"
        assertEquals(wiki, ChatLinks.find(wiki).single().openUrl)
        val wrapped = ChatLinks.find("(https://example.com)")
        assertEquals("https://example.com", wrapped.single().openUrl)
        assertEquals(1, wrapped.single().start)
    }

    @Test
    fun skipsEmailBareSchemesAndTheMiddleOfAWord() {
        assertTrue(ChatLinks.find("a@b.com and http:// and https:// and www.").isEmpty())
        assertTrue(ChatLinks.find("nothttps://example.com").isEmpty())
    }

    @Test
    fun annotatedLineKeepsTheLinkAndTheSender() {
        val line = chatLineAnnotated("Ada", "look https://example.com/x.", Color.Blue, openLinks = true)
        assertTrue(line.text.startsWith("Ada: look "))
        val links = line.getLinkAnnotations(0, line.length)
        assertEquals(1, links.size)
        assertEquals("https://example.com/x", (links[0].item as LinkAnnotation.Url).url)
        assertTrue(line.text.endsWith("."))
        val plain = chatLineAnnotated(null, "https://example.com", Color.Blue, openLinks = false)
        assertTrue(plain.getLinkAnnotations(0, plain.length).isEmpty())
        assertEquals("https://example.com", plain.text)
    }
}
