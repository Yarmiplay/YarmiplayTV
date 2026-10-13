package com.yarmiplaytv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * What the player draws straight on the video, whatever the theme: white, on see-through black where it's text, so it
 * reads on a bright picture as well as a dark one. Panels, sheets and buttons over the video take the theme.
 */
object OnVideo {
    val Text = Color.White
    val Background = Color.Black.copy(alpha = 0.55f)
}

/** A label on the video: [OnVideo.Background] behind it (its text is [OnVideo.Text]). */
fun Modifier.onVideoLabel(): Modifier =
    clip(RoundedCornerShape(6.dp)).background(OnVideo.Background).padding(horizontal = 8.dp, vertical = 2.dp)
