package com.yarmiplaytv.ui.mobile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.tracking.SkipPrompt
import com.yarmiplaytv.tracking.SkipService
import com.yarmiplaytv.ui.theme.AppColors

/**
 * Where the Skip button sits on the phone and desktop player: bottom right, just above the controls' bottom bar,
 * in the same place whether the controls show or not, so it never jumps under the pointer or finger.
 */
val SkipButtonBottom = 136.dp

/**
 * "Skip opening" while the Skip button is offered (see [SkipService]), with a line along its bottom running out
 * over the seconds it stays. [hint] is the key that presses it (e.g. the desktop's S).
 */
@Composable
fun SkipSegmentButton(container: AppContainer, modifier: Modifier = Modifier, hint: String? = null) {
    val prompt by container.skips.prompt.collectAsStateWithLifecycle()
    prompt?.let { SkipSegmentButton(it, container.skips::skip, modifier, hint) }
}

@Composable
fun SkipSegmentButton(shown: SkipPrompt, onSkip: () -> Unit, modifier: Modifier = Modifier, hint: String? = null) {
    SkipChip(
        shown.label,
        key = shown,
        modifier = modifier.clip(SkipChipShape).clickable(onClick = onSkip).testTag("skip_segment"),
        hint = hint,
    )
}

private val SkipChipShape = RoundedCornerShape(12.dp)

/**
 * The Skip button's look, shared by every player (TV draws it unfocusable and presses it with OK): dark glass like
 * the player's other overlays, the skip icon in the accent colour, the key as a keycap, and the time left as a line
 * along the bottom. [key] restarts the countdown when the prompt changes; [large] is the TV size.
 */
@Composable
fun SkipChip(label: String, key: Any, modifier: Modifier = Modifier, hint: String? = null, large: Boolean = false) {
    val left = remember(key) { Animatable(1f) }
    LaunchedEffect(key) { left.animateTo(0f, tween(SkipService.PROMPT_MILLIS.toInt(), easing = LinearEasing)) }
    val shape = if (large) RoundedCornerShape(14.dp) else SkipChipShape
    Box(
        modifier
            .clip(shape)
            .background(Color(0xE6121419))
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape),
    ) {
        Row(
            Modifier.padding(
                start = if (large) 18.dp else 12.dp,
                end = if (large) 20.dp else 14.dp,
                top = if (large) 12.dp else 9.dp,
                bottom = if (large) 14.dp else 11.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.SkipNext, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(if (large) 28.dp else 20.dp))
            Spacer(Modifier.width(if (large) 10.dp else 8.dp))
            Text(
                label,
                color = Color.White,
                style = TextStyle(fontSize = if (large) 20.sp else 15.sp, fontWeight = FontWeight.SemiBold),
            )
            hint?.let {
                Spacer(Modifier.width(if (large) 14.dp else 10.dp))
                Keycap(it, large)
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(left.value).height(3.dp).background(AppColors.Accent))
    }
}

/** A key name drawn like a key. */
@Composable
private fun Keycap(text: String, large: Boolean) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.8f),
        style = TextStyle(fontSize = if (large) 14.sp else 11.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(5.dp))
            .padding(horizontal = if (large) 7.dp else 5.dp, vertical = 1.dp),
    )
}
