package com.yarmiplaytv.ui.splash

import com.yarmiplaytv.DeviceKind
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Cold-start switch-on intro for Android phones, tablets and Android TV.
 *
 * Times, easing and geometry follow the approved `splash.html` preview (the cut with the wordmark):
 * https://gist.github.com/Yarmiplay/d5b00ca4cfa12d9f8021b0f87f13c6b5
 * [LaunchSplash] paints [frame]; the desktop and browser apps do not use this.
 */
object SplashTimeline {
    /** Full play, including the still hand-off at the end. Seconds. */
    const val DURATION_SEC = 2.10

    /**
     * How long the still end frame stays up when the system animator scale is off
     * ("remove animations"). Long enough to read the logo, short of a second animation.
     */
    const val REDUCED_MOTION_HOLD_MS = 450L

    val background = Rgb(14.0, 17.0, 22.0) // #0E1116
    val off = Rgb(2.0, 3.0, 5.0) // #020305
    val accent = Rgb(61.0, 165.0, 244.0) // #3DA5F4
    val cyan = Rgb(165.0, 243.0, 252.0) // #A5F3FC
    val white = Rgb(255.0, 255.0, 255.0)
    val wordmarkName = Rgb(232.0, 236.0, 242.0) // #E8ECF2
    val wordmarkTv = accent

    const val WORDMARK_NAME = "Yarmiplay"
    const val WORDMARK_TV = "TV"

    /** Core of the dot is this far from [cyan] toward [white] (cyan-white, not a flat accent). */
    const val DOT_CORE_WHITE_MIX = 0.70

    /** Screen-pixel shadow blur of the dot, then the tighter cyan core. Chrome's shadowBlur. */
    const val DOT_SHADOW_BLUR = 46.0
    const val DOT_CORE_BLUR = 14.0

    /** Tube edge glow, screen pixels, as the line opens. */
    const val TUBE_EDGE_BLUR_START = 30.0
    const val TUBE_EDGE_BLUR_END = 70.0

    /** Warm-up wash is accent mixed toward cyan, never white, and peaks at this opacity. */
    const val FLASH_CYAN_MIX = 0.55
    const val FLASH_CENTER_ALPHA = 0.80

    /** Prism glow blur as a fraction of the settled prism height, in screen pixels (not scaled by the spring). */
    const val PRISM_GLOW_BLUR_FRACTION = 0.22

    /** Phosphor flicker while the tube warms up. Indexed by the 60 fps frame. */
    val flicker = doubleArrayOf(1.0, 0.86, 1.0, 0.93, 1.0, 1.0, 0.9, 1.0, 0.97, 1.0)

    fun prismHeight(width: Double, height: Double, form: SplashForm): Double {
        val short = min(width, height)
        return if (form == SplashForm.Tv) 0.30 * height else 0.32 * short
    }

    fun crtHeight(width: Double, height: Double, form: SplashForm): Double =
        if (form == SplashForm.Tv) 0.58 * height else 0.60 * min(width, height)

    fun wordmarkSize(width: Double, height: Double, form: SplashForm): Double =
        if (form == SplashForm.Tv) 0.072 * height else 0.085 * min(width, height)

    /** First frame of a run. Remove-animations holds the last frame instead of playing. */
    fun startTime(animationsEnabled: Boolean): Double = if (animationsEnabled) 0.0 else DURATION_SEC

    fun frame(t: Double, width: Double, height: Double, form: SplashForm): Frame {
        val prismH = prismHeight(width, height, form)
        val screenH = crtHeight(width, height, form)
        val screenW = screenH * 4.0 / 3.0
        val font = wordmarkSize(width, height, form)
        val cx = width / 2.0
        val cy = height / 2.0
        val frameIndex = round(t * 60.0).toInt()
        val flick = if (t > 0.52 && t < 0.80) flicker[Math.floorMod(frameIndex, flicker.size)] else 1.0

        val kDot = seg(t, 0.10, 0.28)
        val kLine = outExpo(seg(t, 0.24, 0.46))
        val kOpen = outQuart(seg(t, 0.44, 0.78))
        val kExp = inOutCubic(seg(t, 1.16, 1.62))

        val dot = if (t >= 0.10 && t < 0.46) {
            val fade = if (t > 0.40) lerp(1.0, 0.85, seg(t, 0.40, 0.46)) else 1.0
            val alpha = outCubic(kDot) * fade
            val coreH = if (t < 0.24) lerp(4.0, 18.0, outCubic(kDot)) else lerp(18.0, 5.0, kLine)
            val coreW = if (t < 0.24) coreH else maxOf(coreH, lerp(10.0, screenW, kLine))
            val opening = seg(t, 0.44, 0.46)
            Dot(
                alpha = alpha,
                coreW = coreW,
                coreH = coreH,
                coreDrawH = coreH + opening * 6.0,
                coreRadius = coreH / 2.0,
                coreColor = cyan.mix(white, DOT_CORE_WHITE_MIX),
                bloomRx = lerp(150.0, screenW * 0.75, kLine),
                bloomRy = lerp(150.0, 90.0, kLine),
            )
        } else {
            null
        }

        val tube = if (t >= 0.44) {
            val lineW = lerp(10.0, screenW, kLine)
            val openH = lerp(3.0, screenH, kOpen)
            val reach = hypot(width, height) * 1.25
            val sw = lerp(lineW, reach, kExp)
            val sh = lerp(openH, reach, kExp)
            val rad = lerp(min(openH / 2.0, screenH * 0.09), screenH * 0.6, kExp)
            val edge = (1.0 - kExp) * lerp(1.0, 0.45, seg(t, 0.50, 1.0))
            val glowAmt = (1.0 - seg(t, 1.20, 1.80)) * flick
            val flash = if (t < 0.44) 0.0 else exp(-(t - 0.44) * 6.0)
            val scan = 0.16 * (1.0 - seg(t, 1.05, 1.40)) * seg(t, 0.50, 0.62)
            val y = cy - sh / 2.0
            Tube(
                x = cx - sw / 2.0,
                y = y,
                w = sw,
                h = sh,
                radius = rad,
                edge = edge,
                edgeBlur = lerp(TUBE_EDGE_BLUR_START, TUBE_EDGE_BLUR_END, kOpen),
                edgeShadowAlpha = 0.55 * edge * flick,
                faceCenter = background.mix(Rgb(22.0, 62.0, 108.0), glowAmt),
                faceMid = background.mix(Rgb(16.0, 34.0, 56.0), glowAmt),
                faceRadius = maxOf(sw, sh) * 0.62,
                flash = flash,
                flashColor = accent.mix(cyan, FLASH_CYAN_MIX),
                scanAlpha = scan,
                bandY = lerp(y - 120.0, y + sh + 120.0, seg(t, 0.62, 1.10)),
                prism = if (t >= 0.70) {
                    val tau = t - 0.70
                    val scale = 1.0 - 0.30 * exp(-8.5 * tau) * cos(15.5 * tau)
                    val appear = outCubic(seg(t, 0.70, 0.84))
                    Prism(
                        scale = scale,
                        alpha = appear * flick,
                        glow = lerp(1.0, 0.0, seg(t, 0.80, 1.70)) * appear,
                        unit = prismH / PrismLogo.HEIGHT_UNITS * scale,
                        glowBlurPx = prismH * PRISM_GLOW_BLUR_FRACTION,
                    )
                } else {
                    null
                },
            )
        } else {
            null
        }

        val wordmark = if (t >= 1.48) {
            val k = outCubic(seg(t, 1.48, 1.90))
            Wordmark(
                alpha = k,
                fontPx = font,
                baseline = cy + prismH / 2.0 + font * 1.35 + lerp(font * 0.35, 0.0, k),
                letterSpacingPx = font * lerp(0.06, 0.01, k),
                gapPx = font * 0.02,
            )
        } else {
            null
        }

        return Frame(
            clear = off,
            centerX = cx,
            centerY = cy,
            dot = dot,
            tube = tube,
            wordmark = wordmark,
            grainAlpha = 0.035 * (1.0 - seg(t, 1.55, 1.90)),
            frameIndex = frameIndex,
        )
    }
}

/**
 * Phone and tablet share the portrait-phone geometry (prism is 32% of the short side).
 * TV uses 30% of the screen height, matching the 1920×1080 preview.
 */
enum class SplashForm { Phone, Tv }

fun splashForm(kind: DeviceKind): SplashForm = if (kind == DeviceKind.TV) SplashForm.Tv else SplashForm.Phone

/**
 * Cold start only: a fresh process whose activity is not restoring state, and not an instrumented test
 * (those set `yarmiplaytv.noUpdateCheck` and screenshot the real UI).
 */
fun shouldShowLaunchSplash(restoredInstance: Boolean, alreadyPlayed: Boolean, underTest: Boolean): Boolean =
    !restoredInstance && !alreadyPlayed && !underTest

enum class SplashInput { Key, Pointer }

/** Touch skips on a phone or tablet; a click skips on TV. */
fun splashSkipsOn(form: SplashForm, input: SplashInput): Boolean = when (input) {
    SplashInput.Pointer -> true
    SplashInput.Key -> form == SplashForm.Tv
}

enum class SplashKeyAction { Skip, Ignore, Pass }

/**
 * TV: any remote key, including Back, skips. Phone and tablet: keys don't skip; Back still leaves the app;
 * other keys are swallowed so they don't land on the UI under the overlay.
 */
fun splashKeyAction(form: SplashForm, isBack: Boolean): SplashKeyAction = when {
    splashSkipsOn(form, SplashInput.Key) -> SplashKeyAction.Skip
    isBack -> SplashKeyAction.Pass
    else -> SplashKeyAction.Ignore
}

sealed interface SplashPlayback {
    data object Animated : SplashPlayback
    data class Still(val holdMs: Long) : SplashPlayback
}

fun splashPlayback(animationsEnabled: Boolean): SplashPlayback =
    if (animationsEnabled) SplashPlayback.Animated else SplashPlayback.Still(SplashTimeline.REDUCED_MOTION_HOLD_MS)

data class Rgb(val r: Double, val g: Double, val b: Double) {
    fun mix(other: Rgb, k: Double) = Rgb(
        r + (other.r - r) * k,
        g + (other.g - g) * k,
        b + (other.b - b) * k,
    )

    fun argb(alpha: Double): Int {
        val a = (alpha.coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or
            (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
    }
}

/** The play-prism in `shared/src/androidMain/res/drawable/ic_logo.xml` (108-unit viewport). */
object PrismLogo {
    val a = Vertex(36.48, 26.36)
    val b = Vertex(36.48, 81.64)
    val c = Vertex(81.48, 54.0)
    val m = Vertex(50.30, 54.0)

    /** Optical centre of the triangle. The bbox centre sits further right; the preview recentres on this. */
    const val OPTICAL_X = 56.0
    const val OPTICAL_Y = 54.0

    /** Bottom of the triangle minus the top, the height the preview scales to [SplashTimeline.prismHeight]. */
    const val HEIGHT_UNITS = 55.28

    val bottom = Facet(
        m, c, b,
        from = Vertex(65.89, 54.0),
        to = Vertex(36.48, 81.64),
        stops = listOf(0.0 to 0x0284C7, 0.5 to 0x0369A1, 1.0 to 0x075985),
    )
    val spine = Facet(
        a, m, b,
        from = Vertex(36.48, 26.36),
        to = Vertex(36.48, 81.64),
        stops = listOf(0.0 to 0x38BDF8, 0.5 to 0x0EA5E9, 1.0 to 0x0284C7),
    )
    val top = Facet(
        a, c, m,
        from = Vertex(36.48, 26.36),
        to = Vertex(81.48, 40.18),
        stops = listOf(0.0 to 0xA5F3FC, 0.35 to 0x67E8F9, 1.0 to 0x38BDF8),
    )

    val creases = listOf(
        Crease(m, c, 0xFFFFFF, 0.80, 0.38),
        Crease(a, m, 0xFFFFFF, 0.60, 0.32),
        Crease(b, m, 0x38BDF8, 0.50, 0.26),
    )
}

data class Vertex(val x: Double, val y: Double)

data class Facet(
    val p: Vertex,
    val q: Vertex,
    val r: Vertex,
    val from: Vertex,
    val to: Vertex,
    val stops: List<Pair<Double, Int>>,
)

data class Crease(val p: Vertex, val q: Vertex, val rgb: Int, val alpha: Double, val width: Double)

data class Frame(
    val clear: Rgb,
    val centerX: Double,
    val centerY: Double,
    val dot: Dot?,
    val tube: Tube?,
    val wordmark: Wordmark?,
    val grainAlpha: Double,
    val frameIndex: Int,
)

data class Dot(
    val alpha: Double,
    val coreW: Double,
    val coreH: Double,
    val coreDrawH: Double,
    val coreRadius: Double,
    val coreColor: Rgb,
    val bloomRx: Double,
    val bloomRy: Double,
)

data class Tube(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val radius: Double,
    val edge: Double,
    val edgeBlur: Double,
    val edgeShadowAlpha: Double,
    val faceCenter: Rgb,
    val faceMid: Rgb,
    val faceRadius: Double,
    val flash: Double,
    val flashColor: Rgb,
    val scanAlpha: Double,
    val bandY: Double,
    val prism: Prism?,
)

data class Prism(
    val scale: Double,
    val alpha: Double,
    val glow: Double,
    /** Screen pixels per logo unit, including the spring scale. */
    val unit: Double,
    val glowBlurPx: Double,
)

data class Wordmark(
    val alpha: Double,
    val fontPx: Double,
    val baseline: Double,
    val letterSpacingPx: Double,
    val gapPx: Double,
    val name: String = SplashTimeline.WORDMARK_NAME,
    val tv: String = SplashTimeline.WORDMARK_TV,
    val nameColor: Rgb = SplashTimeline.wordmarkName,
    val tvColor: Rgb = SplashTimeline.wordmarkTv,
)

private fun seg(t: Double, a: Double, b: Double) = ((t - a) / (b - a)).coerceIn(0.0, 1.0)

private fun lerp(a: Double, b: Double, k: Double) = a + (b - a) * k

private fun outCubic(k: Double) = 1.0 - (1.0 - k).pow(3)

private fun outQuart(k: Double) = 1.0 - (1.0 - k).pow(4)

private fun outExpo(k: Double) = if (k >= 1.0) 1.0 else 1.0 - 2.0.pow(-10.0 * k)

private fun inOutCubic(k: Double) = if (k < 0.5) 4.0 * k * k * k else 1.0 - (-2.0 * k + 2.0).pow(3) / 2.0
