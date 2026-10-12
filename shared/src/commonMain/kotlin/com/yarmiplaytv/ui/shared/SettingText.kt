package com.yarmiplaytv.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** How long after the last keystroke a settings field saves. */
const val SETTING_SAVE_DELAY_MS = 400L

/**
 * The text of a settings field. It shows what's typed right away and saves it with [onSave] once typing stops
 * (or when the field leaves the screen), instead of showing the saved value: that one arrives a moment later,
 * and redrawing the field with it put the caret back before the letter just typed. A [value] saved elsewhere
 * replaces the text while nothing typed here is waiting to be saved.
 */
@Composable
fun rememberSavedText(value: String, onSave: (String) -> Unit): MutableState<String> {
    val text = remember { mutableStateOf(value) }
    var saved by remember { mutableStateOf(value) }
    val save by rememberUpdatedState(onSave)
    LaunchedEffect(value) {
        if (text.value == saved) text.value = value
        saved = value
    }
    LaunchedEffect(text.value) {
        if (text.value == saved) return@LaunchedEffect
        delay(SETTING_SAVE_DELAY_MS)
        saved = text.value
        save(text.value)
    }
    DisposableEffect(Unit) {
        onDispose { if (text.value != saved) save(text.value) }
    }
    return text
}
