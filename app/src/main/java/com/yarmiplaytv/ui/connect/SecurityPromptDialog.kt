package com.yarmiplaytv.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.SecurityAnswer
import com.yarmiplaytv.ui.components.ActionButton
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.ui.theme.SurfaceInk
import kotlinx.coroutines.delay

/**
 * Shown over any screen while the app asks before a connection that wouldn't be private
 * ([com.yarmiplaytv.SecurityPrompts]). Focus starts on Cancel, so a stray OK press doesn't agree.
 */
@Composable
fun SecurityPromptDialog(container: AppContainer) {
    val pending by container.securityPrompts.current.collectAsStateWithLifecycle()
    val prompt = pending ?: return
    val question = prompt.question
    val cancel = remember(prompt) { FocusRequester() }
    LaunchedEffect(prompt) { runCatching { delay(50); cancel.requestFocus() } }
    fun answer(answer: SecurityAnswer) = container.securityPrompts.answer(prompt, answer)
    Dialog(onDismissRequest = { answer(SecurityAnswer.CANCEL) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.width(860.dp).clip(RoundedCornerShape(20.dp)).background(AppColors.Surface).padding(32.dp).testTag("security_prompt")) {
            SurfaceInk {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(question.title, style = MaterialTheme.typography.headlineSmall)
                    Text(question.text, color = AppColors.TextDim)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ActionButton(question.cancel, { answer(SecurityAnswer.CANCEL) }, Modifier.focusRequester(cancel).testTag("security_cancel"), primary = true)
                        ActionButton(question.confirm, { answer(SecurityAnswer.CONFIRM) }, Modifier.testTag("security_confirm"))
                        question.neverAsk?.let { label ->
                            ActionButton(label, { answer(SecurityAnswer.NEVER_ASK) }, Modifier.testTag("security_never_ask"))
                        }
                    }
                }
            }
        }
    }
}
