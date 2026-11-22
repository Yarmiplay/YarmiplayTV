package com.yarmiplaytv.ui.mobile

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.SecurityAnswer

/** Shown over any screen while the app asks before a connection that wouldn't be private ([com.yarmiplaytv.SecurityPrompts]). */
@Composable
fun MobileSecurityPromptDialog(container: AppContainer) {
    val pending by container.securityPrompts.current.collectAsStateWithLifecycle()
    val prompt = pending ?: return
    val question = prompt.question
    fun answer(answer: SecurityAnswer) = container.securityPrompts.answer(prompt, answer)
    AlertDialog(
        onDismissRequest = { answer(SecurityAnswer.CANCEL) },
        title = { Text(question.title) },
        text = { Text(question.text) },
        // Separate buttons, not a Row: the dialog's button row wraps them on a narrow phone.
        confirmButton = {
            question.neverAsk?.let { label ->
                TextButton({ answer(SecurityAnswer.NEVER_ASK) }, Modifier.testTag("security_never_ask")) { Text(label) }
            }
            TextButton({ answer(SecurityAnswer.CONFIRM) }, Modifier.testTag("security_confirm")) { Text(question.confirm) }
        },
        dismissButton = { TextButton({ answer(SecurityAnswer.CANCEL) }, Modifier.testTag("security_cancel")) { Text(question.cancel) } },
        modifier = Modifier.testTag("security_prompt"),
    )
}
