package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yarmiplaytv.ui.theme.AppColors
import com.yarmiplaytv.ui.theme.SurfaceInk

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = AppColors.TextDim, modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
/** A home screen card: what it is and how it stands, all of it shown (the text wraps rather than being cut off). */
fun StatusCard(icon: ImageVector, title: String, detail: String, dot: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface, contentColor = AppColors.OnSurface),
        shape = RoundedCornerShape(16.dp),
    ) {
        // Fills the card when a grid row makes it taller than its text, with the text in the middle.
        SurfaceInk {
        Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = AppColors.Accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = AppColors.TextDim)
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        }
        }
    }
}

@Composable
fun PosterTile(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    aspectRatio: Float,
    placeholder: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    /** At most this many lines of the title and of the subtitle; the rest is cut off with an ellipsis. */
    titleLines: Int = 2,
    subtitleLines: Int = 1,
) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(aspectRatio).clip(RoundedCornerShape(12.dp)).background(AppColors.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(placeholder, contentDescription = null, tint = AppColors.TextDim, modifier = Modifier.size(36.dp))
            if (imageUrl != null) {
                AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(AppColors.Scrim).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = titleLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextDim, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * An entry in a side list of sections, like the Settings categories on wide windows and the desktop's sections: the
 * chosen one on a lighter background with its icon in the accent colour.
 */
@Composable
fun SideNavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(icon, null) },
        selected = selected,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = AppColors.SurfaceHigh,
            unselectedContainerColor = Color.Transparent,
            selectedIconColor = AppColors.Accent,
            selectedTextColor = AppColors.Text,
        ),
        modifier = modifier.padding(vertical = 2.dp),
    )
}

@Composable
fun ReadyChip(ready: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = ready,
        onClick = onClick,
        label = { Text(if (ready) "Ready" else "Not ready") },
        leadingIcon = {
            Icon(
                if (ready) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (ready) AppColors.Ready else AppColors.NotReady,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = AppColors.SurfaceHigh.copy(alpha = 0.6f),
            selectedContainerColor = AppColors.Ready.copy(alpha = 0.18f),
            labelColor = AppColors.Text,
            selectedLabelColor = AppColors.Text,
        ),
        modifier = modifier,
    )
}

@Composable
fun EmptyMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = AppColors.TextDim, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun FormColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}
