package com.yarmiplaytv.ui.splash

import android.animation.ValueAnimator
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.res.ResourcesCompat
import com.yarmiplaytv.R
import kotlin.math.min
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

/**
 * Full-screen intro drawn over the real UI. Startup keeps going underneath; this only waits out the
 * timeline (or the short still frame when animations are removed) and then calls [onFinished].
 * A touch or click skips. TV remote keys are handled by the activity, because they never become pointers.
 */
@Composable
fun LaunchSplash(
    form: SplashForm,
    animationsEnabled: Boolean = ValueAnimator.areAnimatorsEnabled(),
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val typeface = remember {
        runCatching { ResourcesCompat.getFont(context, R.font.inter_semibold) }.getOrNull()
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    val canvas = remember { SplashCanvas() }
    DisposableEffect(canvas) { onDispose { canvas.dispose() } }
    var t by remember { mutableDoubleStateOf(SplashTimeline.startTime(animationsEnabled)) }
    LaunchedEffect(animationsEnabled) {
        when (val play = splashPlayback(animationsEnabled)) {
            SplashPlayback.Animated -> {
                val start = withFrameNanos { it }
                while (isActive) {
                    val now = withFrameNanos { it }
                    val elapsed = (now - start) / 1_000_000_000.0
                    t = min(elapsed, SplashTimeline.DURATION_SEC)
                    if (elapsed >= SplashTimeline.DURATION_SEC) break
                }
            }
            is SplashPlayback.Still -> delay(play.holdMs)
        }
        onFinished()
    }
    Canvas(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = "YarmiplayTV" }
            .pointerInput(onFinished) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    // Release, not down: the finger-up must not also click whatever is underneath.
                    if (splashSkipsOn(form, SplashInput.Pointer)) onFinished()
                }
            },
    ) {
        val frame = SplashTimeline.frame(t, size.width.toDouble(), size.height.toDouble(), form)
        canvas.draw(this, frame, typeface)
    }
}
