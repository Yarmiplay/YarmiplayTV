package com.yarmiplaytv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.yarmiplaytv.sync.FeedMessage
import com.yarmiplaytv.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

val CardShape = RoundedCornerShape(12.dp)

@Composable
fun focusBorder() = ClickableSurfaceDefaults.border(
    focusedBorder = Border(BorderStroke(3.dp, AppColors.Accent), shape = CardShape),
)

@Composable
fun cardColors(container: Color = AppColors.Surface) = ClickableSurfaceDefaults.colors(
    containerColor = container,
    contentColor = AppColors.Text,
    focusedContainerColor = AppColors.SurfaceHigh,
    focusedContentColor = Color.White,
    pressedContainerColor = AppColors.SurfaceHigh,
)

/** Focusable rounded tile used for most interactive elements. */
@Composable
fun TvTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    container: Color = AppColors.Surface,
    focusedScale: Float = 1.04f,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = cardColors(container),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = focusBorder(),
    ) { content() }
}

@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    primary: Boolean = false,
    tint: Color? = null,
) {
    TvTile(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        container = if (primary) AppColors.Accent.copy(alpha = 0.25f) else AppColors.SurfaceHigh,
        focusedScale = 1.06f,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = tint ?: LocalContentColor.current, modifier = Modifier.size(24.dp))
            Text(text, style = MaterialTheme.typography.titleSmall, color = if (enabled) AppColors.Text else AppColors.TextDim)
        }
    }
}

@Composable
fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = AppColors.Text, enabled: Boolean = true) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(48.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = AppColors.Accent,
            contentColor = tint,
            focusedContentColor = AppColors.OnAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.align(Alignment.Center).size(26.dp))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = AppColors.TextDim,
        modifier = modifier.padding(vertical = 8.dp),
    )
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

/**
 * D-pad friendly text field: navigating onto it does not pop up the keyboard; pressing OK does.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onSubmit: (() -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val tileFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) {
        if (editing) {
            fieldFocus.requestFocus()
            keyboard?.show()
        }
    }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = AppColors.TextDim, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        TvTile(
            onClick = { editing = true },
            modifier = Modifier.fillMaxWidth().focusRequester(tileFocus),
            container = AppColors.SurfaceHigh,
            focusedScale = 1.02f,
        ) {
            Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                if (value.isEmpty() && !editing) {
                    Text(placeholder, color = AppColors.TextDim, fontSize = 20.sp)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = AppColors.Text, fontSize = 20.sp),
                    cursorBrush = SolidColor(AppColors.Accent),
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (password) KeyboardType.Password else keyboardType,
                        imeAction = if (onSubmit != null) ImeAction.Go else ImeAction.Done,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { editing = false; keyboard?.hide(); tileFocus.requestFocus() },
                        onGo = { editing = false; keyboard?.hide(); tileFocus.requestFocus(); onSubmit?.invoke() },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus)
                        .focusProperties { canFocus = editing }
                        .onFocusChanged { if (!it.isFocused && editing) editing = false },
                )
            }
        }
    }
}

/** Two-state row used in settings. */
@Composable
fun ToggleRow(title: String, checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    ValueRow(title, if (checked) "On" else "Off", onToggle, modifier, subtitle, valueColor = if (checked) AppColors.Ready else AppColors.TextDim)
}

@Composable
fun ValueRow(title: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, valueColor: Color = AppColors.Accent) {
    TvTile(onClick = onClick, modifier = modifier.fillMaxWidth(), focusedScale = 1.02f) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextDim)
            }
            Spacer(Modifier.width(16.dp))
            Pill(value, valueColor)
        }
    }
}

/** Poster/thumbnail card with a title underneath. */
@Composable
fun PosterCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    aspectRatio: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholderIcon: ImageVector? = null,
    badge: String? = null,
) {
    Column(modifier) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio),
            shape = ClickableSurfaceDefaults.shape(CardShape),
            colors = cardColors(AppColors.SurfaceHigh),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            border = focusBorder(),
        ) {
            if (placeholderIcon != null) {
                Icon(placeholderIcon, contentDescription = null, tint = AppColors.TextDim, modifier = Modifier.align(Alignment.Center).size(48.dp))
            }
            if (imageUrl != null) {
                AsyncImage(model = imageUrl, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (badge != null) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { Pill(badge, AppColors.Accent) }
            }
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp))
        }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = SurfaceDefaults.colors(containerColor = AppColors.Surface.copy(alpha = 0.96f), contentColor = AppColors.Text),
    ) { content() }
}

/** Transient notifications (chat, joins, pauses) stacked in a corner. */
@Composable
fun ToastHost(toasts: Flow<FeedMessage>, modifier: Modifier = Modifier) {
    val visible = remember { mutableStateListOf<FeedMessage>() }
    LaunchedEffect(toasts) {
        toasts.collect { msg ->
            visible.add(msg)
            while (visible.size > 4) visible.removeAt(0)
        }
    }
    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) {
            delay(5000)
            if (visible.isNotEmpty()) visible.removeAt(0)
        }
    }
    Column(modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        visible.forEach { msg ->
            AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xE6161A21))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (msg.from != null) {
                        Text("${msg.from}: ", color = AppColors.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(msg.text, color = if (msg.isError) AppColors.Error else AppColors.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit = {}) {
    Column(modifier.padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = AppColors.TextDim)
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
