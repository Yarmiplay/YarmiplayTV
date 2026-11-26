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

/** Name plus message, with link and optional clickable-timestamp annotations on the message body. */
fun chatLineAnnotated(
    sender: String?,
    body: String,
    linkColor: Color,
    openLinks: Boolean,
    italic: Boolean = false,
    onTimestampClick: ((Int) -> Unit)? = null,
    timestampColor: Color = linkColor,
): AnnotatedString = buildAnnotatedString {
    if (sender != null) {
        withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold)) { append(sender) }
        append(": ")
    }
    appendLinked(body, linkColor, openLinks, italic, onTimestampClick, timestampColor)
}

private fun AnnotatedString.Builder.appendLinked(
    body: String,
    linkColor: Color,
    openLinks: Boolean,
    italic: Boolean,
    onTimestampClick: ((Int) -> Unit)?,
    timestampColor: Color,
) {
    val spans = ChatSpans.find(body, includeLinks = openLinks, includeTimestamps = onTimestampClick != null)
    if (spans.isEmpty()) {
        if (italic) withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(body) } else append(body)
        return
    }
    var cursor = 0
    for (span in spans) {
        appendChunk(body.substring(cursor, span.start), italic)
        when (span) {
            is ChatSpan.Url -> {
                pushLink(
                    LinkAnnotation.Url(
                        span.openUrl,
                        TextLinkStyles(
                            style = SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                                fontStyle = if (italic) FontStyle.Italic else null,
                            ),
                        ),
                    ),
                )
                append(body.substring(span.start, span.end))
                pop()
            }
            is ChatSpan.Timestamp -> {
                val click = onTimestampClick
                if (click != null) {
                    pushLink(
                        LinkAnnotation.Clickable(
                            tag = "ts:${span.seconds}",
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = timestampColor,
                                    textDecoration = TextDecoration.Underline,
                                    fontWeight = FontWeight.Medium,
                                    fontStyle = if (italic) FontStyle.Italic else null,
                                ),
                            ),
                            linkInteractionListener = { click(span.seconds) },
                        ),
                    )
                    append(body.substring(span.start, span.end))
                    pop()
                } else {
                    appendChunk(body.substring(span.start, span.end), italic)
                }
            }
        }
        cursor = span.end
    }
    appendChunk(body.substring(cursor), italic)
}

/**
 * Seeks the player (and Syncplay room, if connected and allowed) to [seconds] when a chat timestamp is clicked.
 * Shows a concise room feed notice if no video is loaded, the user cannot seek in a managed room, or the time is past EOF.
 */
fun seekToChatTimestamp(container: com.yarmiplaytv.AppContainer, seconds: Int) {
    val label = formatChatTimestamp(seconds.toDouble())
    if (!container.player.isFileLoaded) {
        container.sync.postLocal("Load a video to jump to $label")
        return
    }
    val room = container.sync.room.value
    if (room.status == com.yarmiplaytv.syncplay.ConnectionStatus.CONNECTED && room.followsOperators) {
        container.sync.postLocal("Only operators can seek in this managed room")
        return
    }
    val duration = container.player.duration
    if (duration > 0.0 && seconds > duration) {
        container.sync.postLocal("Timestamp $label is past the end of this video")
        return
    }
    container.player.seek(seconds.toDouble())
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

/**
 * A chat name's colour: one of the theme's accent, green and amber, the same for a name every time, so who said what
 * is easy to follow down the list. Those three are tuned for contrast in every theme.
 */
@androidx.compose.runtime.Composable
fun chatNameColor(name: String): Color {
    val colors = listOf(com.yarmiplaytv.ui.theme.AppColors.Accent, com.yarmiplaytv.ui.theme.AppColors.Ready, com.yarmiplaytv.ui.theme.AppColors.NotReady)
    return colors[Math.floorMod(name.hashCode(), colors.size)]
}
