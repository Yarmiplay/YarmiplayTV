package com.yarmiplaytv.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/** Name plus message, with link annotations on the message body when [openLinks] is true. */
fun chatLineAnnotated(
    sender: String?,
    body: String,
    linkColor: Color,
    openLinks: Boolean,
    italic: Boolean = false,
): AnnotatedString = buildAnnotatedString {
    if (sender != null) {
        withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold)) { append(sender) }
        append(": ")
    }
    appendLinked(body, linkColor, openLinks, italic)
}

private fun AnnotatedString.Builder.appendLinked(
    body: String,
    linkColor: Color,
    openLinks: Boolean,
    italic: Boolean,
) {
    val links = if (openLinks) ChatLinks.find(body) else emptyList()
    if (links.isEmpty()) {
        if (italic) withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(body) } else append(body)
        return
    }
    var cursor = 0
    for (link in links) {
        appendChunk(body.substring(cursor, link.start), italic)
        pushLink(
            LinkAnnotation.Url(
                link.openUrl,
                TextLinkStyles(
                    style = SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                        fontStyle = if (italic) FontStyle.Italic else null,
                    ),
                ),
            ),
        )
        append(body.substring(link.start, link.end))
        pop()
        cursor = link.end
    }
    appendChunk(body.substring(cursor), italic)
}

private fun AnnotatedString.Builder.appendChunk(chunk: String, italic: Boolean) {
    if (chunk.isEmpty()) return
    if (italic) withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(chunk) } else append(chunk)
}

/**
 * One chat message. Desktop selects and copies the text and adds Block to the right-click menu.
 * Phone and tablet draw the same text so links stay tappable, without a selection container.
 */
@Composable
expect fun ChatMessageText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = TextStyle.Default,
    blockLabel: String? = null,
    onBlock: (() -> Unit)? = null,
)
