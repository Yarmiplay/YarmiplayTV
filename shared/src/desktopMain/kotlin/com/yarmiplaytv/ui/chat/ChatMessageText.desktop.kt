package com.yarmiplaytv.ui.chat

import androidx.compose.foundation.ContextMenuDataProvider
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle

@Composable
actual fun ChatMessageText(
    text: AnnotatedString,
    modifier: Modifier,
    color: Color,
    style: TextStyle,
    blockLabel: String?,
    onBlock: (() -> Unit)?,
) {
    val message = @Composable {
        SelectionContainer(modifier) {
            Text(text, color = color, style = style)
        }
    }
    if (onBlock != null && !blockLabel.isNullOrEmpty()) {
        ContextMenuDataProvider({ listOf(ContextMenuItem(blockLabel, onBlock)) }) { message() }
    } else {
        message()
    }
}
