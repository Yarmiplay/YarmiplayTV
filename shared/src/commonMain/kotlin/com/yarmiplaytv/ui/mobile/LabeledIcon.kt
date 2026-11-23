package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yarmiplaytv.ui.theme.LocalIconLabels
import com.yarmiplaytv.ui.theme.onVideoLabel

/**
 * An icon button that, with Labels under icons on (the default), shows [label] in small type under the icon: one
 * line, at most [LabelMaxWidth] wide, so a row of them stays about as compact as the icons alone. Off, it's a plain
 * [IconButton]. Either way [label] is what a screen reader says.
 */
@Composable
fun LabeledIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    /** On the video: the label gets the same shadow as the clocks so it reads on any picture. */
    onVideo: Boolean = false,
    /** A short name for the label when [label] (the screen reader's) is longer than fits. */
    shortLabel: String = label,
) {
    if (!LocalIconLabels.current) {
        // A full 48dp target, like the labelled button's minimum.
        IconButton(onClick, modifier.size(48.dp), enabled = enabled) { Icon(icon, contentDescription = label, tint = tint) }
        return
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        contentColor = tint,
        modifier = modifier.heightIn(min = 48.dp).widthIn(min = 48.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 4.dp, vertical = 4.dp).alpha(if (enabled) 1f else 0.38f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
            Text(
                shortLabel,
                color = tint,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.widthIn(max = LabelMaxWidth).then(if (onVideo) Modifier.onVideoLabel() else Modifier),
            )
        }
    }
}

/** A label wider than this is cut short, so one long name can't push the rest of a row off the screen. */
val LabelMaxWidth = 76.dp
