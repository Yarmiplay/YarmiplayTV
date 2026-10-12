package com.yarmiplaytv.ui.mobile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.tracking.SkipPrompt
import com.yarmiplaytv.tracking.SkipService
import com.yarmiplaytv.ui.theme.AppColors

/**
 * "Skip opening ›" while the Skip button is offered (see [SkipService]), with a line under it running out over the
 * seconds it stays. [hint] follows the label (e.g. the desktop's key).
 */
@Composable
fun SkipSegmentButton(container: AppContainer, modifier: Modifier = Modifier, hint: String? = null) {
    val prompt by container.skips.prompt.collectAsStateWithLifecycle()
    prompt?.let { SkipSegmentButton(it, container.skips::skip, modifier, hint) }
}

@Composable
fun SkipSegmentButton(shown: SkipPrompt, onSkip: () -> Unit, modifier: Modifier = Modifier, hint: String? = null) {
    val left = remember(shown) { Animatable(1f) }
    LaunchedEffect(shown) { left.animateTo(0f, tween(SkipService.PROMPT_MILLIS.toInt(), easing = LinearEasing)) }
    Surface(
        onClick = onSkip,
        shape = RoundedCornerShape(10.dp),
        color = Color.White.copy(alpha = 0.92f),
        contentColor = Color.Black,
        modifier = modifier.testTag("skip_segment"),
    ) {
        Box {
            Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(shown.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                hint?.let { Text("  $it", style = MaterialTheme.typography.labelMedium, color = Color.Black.copy(alpha = 0.55f)) }
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(left.value).height(3.dp).background(AppColors.Accent))
        }
    }
}
