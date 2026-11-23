package com.yarmiplaytv.ui.splash

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * Paints one [Frame] with the same draw order as the preview canvas: flat shapes, radial and linear
 * gradients, and the three prism triangles. Glows use a software bitmap so the blur matches the
 * preview's shadowBlur (a hardware canvas drops it). Grain is a deterministic overlay, not a video.
 */
internal class SplashCanvas {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val path = Path()
    private val clip = Path()
    private val rect = RectF()
    private var bitmap: Bitmap? = null
    private val noise: ImageBitmap = splashNoise()

    fun draw(scope: DrawScope, frame: Frame, typeface: Typeface) {
        val w = scope.size.width.toInt()
        val h = scope.size.height.toInt()
        if (w <= 0 || h <= 0) return
        val bmp = bitmap?.takeIf { it.width == w && it.height == h && !it.isRecycled }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                bitmap?.recycle()
                bitmap = it
                it.density = Bitmap.DENSITY_NONE
            }
        render(Canvas(bmp), h, frame, typeface)
        scope.drawIntoCanvas { canvas ->
            rect.set(0f, 0f, scope.size.width, scope.size.height)
            canvas.nativeCanvas.drawBitmap(bmp, null, rect, null)
        }
        drawGrain(scope, frame, w, h)
    }

    fun dispose() {
        bitmap?.recycle()
        bitmap = null
    }

    private fun render(c: Canvas, height: Int, frame: Frame, typeface: Typeface) {
        c.drawColor(frame.clear.argb(1.0))
        frame.dot?.let { drawDot(c, frame, it) }
        frame.tube?.let { drawTube(c, frame, it, height) }
        frame.wordmark?.let { drawWordmark(c, frame, it, typeface) }
    }

    private fun drawDot(c: Canvas, frame: Frame, dot: Dot) {
        val cx = frame.centerX.toFloat()
        val cy = frame.centerY.toFloat()
        val a = dot.alpha
        if (a <= 0.0 || dot.bloomRy <= 0.0) return
        c.save()
        c.translate(cx, cy)
        c.scale((dot.bloomRx / dot.bloomRy).toFloat(), 1f)
        val ry = dot.bloomRy.toFloat()
        paint.resetFill()
        paint.shader = RadialGradient(
            0f, 0f, ry,
            intArrayOf(SplashTimeline.accent.argb(0.55 * a), SplashTimeline.accent.argb(0.20 * a), SplashTimeline.accent.argb(0.0)),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(-ry * 1.1f, -ry * 1.1f, ry * 1.1f, ry * 1.1f, paint)
        c.restore()

        val x = (frame.centerX - dot.coreW / 2.0).toFloat()
        val y = (frame.centerY - dot.coreH / 2.0).toFloat()
        val core = dot.coreColor.argb(a)
        drawShadowedRoundRect(
            c, x, y, dot.coreW.toFloat(), dot.coreDrawH.toFloat(), dot.coreRadius.toFloat(),
            fill = core, shadow = SplashTimeline.accent.argb(a), blur = SplashTimeline.DOT_SHADOW_BLUR.toFloat(),
        )
        drawShadowedRoundRect(
            c, x, y, dot.coreW.toFloat(), dot.coreDrawH.toFloat(), dot.coreRadius.toFloat(),
            fill = core, shadow = SplashTimeline.cyan.argb(0.9 * a), blur = SplashTimeline.DOT_CORE_BLUR.toFloat(),
        )
    }

    private fun drawTube(c: Canvas, frame: Frame, tube: Tube, height: Int) {
        val x = tube.x.toFloat()
        val y = tube.y.toFloat()
        val w = tube.w.toFloat()
        val h = tube.h.toFloat()
        val r = tube.radius.toFloat()
        if (tube.edge > 0.01) {
            drawShadowedRoundRect(
                c, x, y, w, h, r,
                fill = SplashTimeline.background.argb(1.0),
                shadow = SplashTimeline.accent.argb(tube.edgeShadowAlpha),
                blur = tube.edgeBlur.toFloat(),
            )
        }
        c.save()
        clipRoundRect(c, x, y, w, h, r)
        paint.resetFill()
        paint.shader = RadialGradient(
            frame.centerX.toFloat(), frame.centerY.toFloat(), tube.faceRadius.toFloat().coerceAtLeast(1f),
            intArrayOf(tube.faceCenter.argb(1.0), tube.faceMid.argb(1.0), SplashTimeline.background.argb(1.0)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(x, y, x + w, y + h, paint)
        if (tube.flash > 0.003) {
            val reach = maxOf(w, h) * 0.7f
            paint.resetFill()
            paint.shader = RadialGradient(
                frame.centerX.toFloat(), frame.centerY.toFloat(), reach.coerceAtLeast(1f),
                intArrayOf(
                    tube.flashColor.argb(SplashTimeline.FLASH_CENTER_ALPHA * tube.flash),
                    SplashTimeline.accent.argb(0.32 * tube.flash),
                    SplashTimeline.accent.argb(0.05 * tube.flash),
                ),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP,
            )
            c.drawRect(x, y, x + w, y + h, paint)
        }
        if (tube.scanAlpha > 0.002) {
            paint.resetFill()
            paint.color = android.graphics.Color.argb((tube.scanAlpha.coerceIn(0.0, 1.0) * 255.0).toInt(), 0, 0, 0)
            val top = kotlin.math.floor(tube.y).toInt()
            val bottom = kotlin.math.ceil(tube.y + tube.h).toInt()
            var yy = top
            if (yy < 0) yy += ((-yy + 3) / 4) * 4
            val visibleBottom = minOf(bottom, height)
            while (yy < visibleBottom) {
                if (yy + 2 > 0) c.drawRect(x, yy.toFloat(), x + w, (yy + 2).toFloat(), paint)
                yy += 4
            }
            val band = tube.bandY.toFloat()
            paint.resetFill()
            paint.shader = LinearGradient(
                0f, band - 160f, 0f, band + 160f,
                intArrayOf(
                    SplashTimeline.accent.argb(0.0),
                    SplashTimeline.accent.argb(0.035 * tube.scanAlpha / 0.16),
                    SplashTimeline.accent.argb(0.0),
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            c.drawRect(x, band - 160f, x + w, band + 160f, paint)
        }
        tube.prism?.let { drawPrism(c, frame, it) }
        if (tube.edge > 0.01 && w > 2f && h > 2f) {
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = SplashTimeline.accent.argb(0.35 * tube.edge)
            drawRoundRect(c, x + 1f, y + 1f, w - 2f, h - 2f, r, paint)
        }
        c.restore()
    }

    private fun drawPrism(c: Canvas, frame: Frame, prism: Prism) {
        if (prism.alpha <= 0.001 && prism.glow <= 0.0) return
        val u = prism.unit.toFloat()
        fun sx(v: Vertex) = ((v.x - PrismLogo.OPTICAL_X) * prism.unit + frame.centerX).toFloat()
        fun sy(v: Vertex) = ((v.y - PrismLogo.OPTICAL_Y) * prism.unit + frame.centerY).toFloat()
        path.rewind()
        path.moveTo(sx(PrismLogo.a), sy(PrismLogo.a))
        path.lineTo(sx(PrismLogo.c), sy(PrismLogo.c))
        path.lineTo(sx(PrismLogo.b), sy(PrismLogo.b))
        path.close()
        if (prism.glow > 0.0 && prism.alpha > 0.0) {
            // Blur in screen pixels. The preview's shadowBlur is not scaled by the logo transform.
            paint.resetFill()
            paint.color = SplashTimeline.accent.argb(0.75 * prism.glow * prism.alpha)
            paint.maskFilter = blur(prism.glowBlurPx.toFloat())
            c.drawPath(path, paint)
            paint.maskFilter = null
        }
        for (facet in listOf(PrismLogo.bottom, PrismLogo.spine, PrismLogo.top)) {
            path.rewind()
            path.moveTo(sx(facet.p), sy(facet.p))
            path.lineTo(sx(facet.q), sy(facet.q))
            path.lineTo(sx(facet.r), sy(facet.r))
            path.close()
            paint.resetFill()
            paint.shader = LinearGradient(
                sx(facet.from), sy(facet.from), sx(facet.to), sy(facet.to),
                IntArray(facet.stops.size) { i -> withAlpha(facet.stops[i].second, prism.alpha) },
                FloatArray(facet.stops.size) { i -> facet.stops[i].first.toFloat() },
                Shader.TileMode.CLAMP,
            )
            c.drawPath(path, paint)
        }
        for (crease in PrismLogo.creases) {
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = (crease.width.toFloat() * u).coerceAtLeast(0.5f)
            paint.color = withAlpha(crease.rgb, crease.alpha * prism.alpha)
            c.drawLine(sx(crease.p), sy(crease.p), sx(crease.q), sy(crease.q), paint)
        }
    }

    private fun drawWordmark(c: Canvas, frame: Frame, mark: Wordmark, typeface: Typeface) {
        if (mark.alpha <= 0.001 || mark.fontPx <= 0.0) return
        paint.reset()
        paint.isAntiAlias = true
        paint.isSubpixelText = true
        paint.typeface = typeface
        paint.textSize = mark.fontPx.toFloat()
        paint.letterSpacing = (mark.letterSpacingPx / mark.fontPx).toFloat()
        val name = mark.name
        val tv = mark.tv
        val nameW = paint.measureText(name)
        val tvW = paint.measureText(tv)
        val gap = mark.gapPx.toFloat()
        val x = frame.centerX.toFloat() - (nameW + gap + tvW) / 2f
        val y = mark.baseline.toFloat()
        paint.color = mark.nameColor.argb(mark.alpha)
        c.drawText(name, x, y, paint)
        paint.color = mark.tvColor.argb(mark.alpha)
        c.drawText(tv, x + nameW + gap, y, paint)
    }

    private fun drawGrain(scope: DrawScope, frame: Frame, width: Int, height: Int) {
        val alpha = frame.grainAlpha.toFloat()
        if (alpha <= 0f) return
        val ox = Math.floorMod(frame.frameIndex * 73, 256)
        val oy = Math.floorMod(frame.frameIndex * 151, 256)
        scope.translate(-ox.toFloat(), -oy.toFloat()) {
            var y = 0
            while (y < height + 256) {
                var x = 0
                while (x < width + 256) {
                    drawImage(
                        noise,
                        dstOffset = IntOffset(x, y),
                        dstSize = IntSize(256, 256),
                        alpha = alpha,
                        blendMode = BlendMode.Overlay,
                    )
                    x += 256
                }
                y += 256
            }
        }
    }

    /**
     * A blurred copy of the shape in [shadow], then the sharp [fill]. Paint alpha and shadow alpha
     * stay independent, which is how the preview's canvas draws a glow.
     */
    private fun drawShadowedRoundRect(
        c: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        r: Float,
        fill: Int,
        shadow: Int,
        blur: Float,
    ) {
        if (blur > 0f && android.graphics.Color.alpha(shadow) > 0) {
            paint.resetFill()
            paint.color = shadow
            paint.maskFilter = blur(blur)
            drawRoundRect(c, x, y, w, h, r, paint)
            paint.maskFilter = null
        }
        paint.resetFill()
        paint.color = fill
        drawRoundRect(c, x, y, w, h, r, paint)
    }

    @Suppress("DEPRECATION")
    private fun blur(radius: Float) = BlurMaskFilter(radius.coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)

    private fun drawRoundRect(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, p: Paint) {
        val radius = minOf(r, w / 2f, h / 2f).coerceAtLeast(0f)
        c.drawRoundRect(x, y, x + w, y + h, radius, radius, p)
    }

    private fun clipRoundRect(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float) {
        val radius = minOf(r, w / 2f, h / 2f).coerceAtLeast(0f)
        clip.rewind()
        rect.set(x, y, x + w, y + h)
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        c.clipPath(clip)
    }

    private fun Paint.resetFill() {
        reset()
        isAntiAlias = true
        isDither = true
        style = Paint.Style.FILL
    }
}

private fun withAlpha(rgb: Int, alpha: Double): Int {
    val a = (alpha.coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceIn(0, 255)
    return (a shl 24) or (rgb and 0x00FFFFFF)
}

/** Deterministic dither so the dark glows don't posterize. A 3.5% overlay, same idea as the preview. */
private fun splashNoise(): ImageBitmap {
    val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(256 * 256)
    var s = 1234567
    for (i in pixels.indices) {
        s = (s * 1103515245 + 12345) and 0x7fffffff
        val v = (s shr 16) and 255
        pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    bmp.setPixels(pixels, 0, 256, 0, 0, 256, 256)
    return bmp.asImageBitmap()
}
