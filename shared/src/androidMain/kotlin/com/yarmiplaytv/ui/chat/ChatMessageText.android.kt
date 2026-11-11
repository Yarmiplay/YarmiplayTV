package com.yarmiplaytv.ui.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle

@Composable
@Suppress("UNUSED_PARAMETER")
actual fun ChatMessageText(
    text: AnnotatedString,
    modifier: Modifier,
    color: Color,
    style: TextStyle,
    blockLabel: String?,
    onBlock: (() -> Unit)?,
) {
    Text(text, modifier = modifier, color = color, style = style)
}
