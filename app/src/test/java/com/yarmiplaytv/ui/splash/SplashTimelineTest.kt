package com.yarmiplaytv.ui.splash

import com.yarmiplaytv.DeviceKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashTimelineTest {
    private val phone = SplashForm.Phone
    private val tv = SplashForm.Tv

    @Test
    fun coldStartOnly() {
        assertTrue(shouldShowLaunchSplash(restoredInstance = false, alreadyPlayed = false, underTest = false))
        assertTrue(!shouldShowLaunchSplash(restoredInstance = true, alreadyPlayed = false, underTest = false))
        assertTrue(!shouldShowLaunchSplash(restoredInstance = false, alreadyPlayed = true, underTest = false))
        assertTrue(!shouldShowLaunchSplash(restoredInstance = false, alreadyPlayed = false, underTest = true))
    }

    @Test
    fun touchSkipsEverywhereAndOnlyTheTvRemoteSkips() {
        assertTrue(splashSkipsOn(phone, SplashInput.Pointer))
        assertTrue(splashSkipsOn(tv, SplashInput.Pointer))
        assertTrue(!splashSkipsOn(phone, SplashInput.Key))
        assertTrue(splashSkipsOn(tv, SplashInput.Key))
        assertEquals(SplashKeyAction.Skip, splashKeyAction(tv, isBack = true))
        assertEquals(SplashKeyAction.Skip, splashKeyAction(tv, isBack = false))
        assertEquals(SplashKeyAction.Pass, splashKeyAction(phone, isBack = true))
        assertEquals(SplashKeyAction.Ignore, splashKeyAction(phone, isBack = false))
        assertEquals(SplashForm.Tv, splashForm(DeviceKind.TV))
        assertEquals(SplashForm.Phone, splashForm(DeviceKind.PHONE))
        assertEquals(SplashForm.Phone, splashForm(DeviceKind.TABLET))
    }

    @Test
    fun removeAnimationsHoldsTheEndFrame() {
        assertEquals(0.0, SplashTimeline.startTime(animationsEnabled = true), 0.0)
        assertEquals(SplashTimeline.DURATION_SEC, SplashTimeline.startTime(animationsEnabled = false), 0.0)
        assertEquals(SplashPlayback.Animated, splashPlayback(animationsEnabled = true))
        val still = splashPlayback(animationsEnabled = false) as SplashPlayback.Still
        assertEquals(450L, still.holdMs)
        assertEquals(SplashTimeline.REDUCED_MOTION_HOLD_MS, still.holdMs)
        assertTrue(still.holdMs in 300L..800L)
    }

    @Test
    fun colorsMatchTheAppBackgroundAndThePreview() {
        assertEquals(0xFF0E1116.toInt(), SplashTimeline.background.argb(1.0))
        assertEquals(0xFF020305.toInt(), SplashTimeline.off.argb(1.0))
        assertEquals(0xFF3DA5F4.toInt(), SplashTimeline.accent.argb(1.0))
        assertEquals(0xFFA5F3FC.toInt(), SplashTimeline.cyan.argb(1.0))
        assertEquals(0xFFE8ECF2.toInt(), SplashTimeline.wordmarkName.argb(1.0))
        assertEquals(SplashTimeline.accent.argb(1.0), SplashTimeline.wordmarkTv.argb(1.0))
    }

    @Test
    fun prismIsTheShortSideOnAPhoneAndTheHeightOnTv() {
        assertEquals(0.32 * 1080.0, SplashTimeline.prismHeight(1080.0, 1920.0, phone), 1e-9)
        assertEquals(0.32 * 1080.0, SplashTimeline.prismHeight(1920.0, 1080.0, phone), 1e-9)
        assertEquals(0.30 * 1080.0, SplashTimeline.prismHeight(1920.0, 1080.0, tv), 1e-9)
        assertEquals(345.6, SplashTimeline.prismHeight(1080.0, 1920.0, phone), 1e-9)
        assertEquals(324.0, SplashTimeline.prismHeight(1920.0, 1080.0, tv), 1e-9)
        val crt = SplashTimeline.crtHeight(1080.0, 1920.0, phone)
        assertEquals(4.0 / 3.0, (crt * 4.0 / 3.0) / crt, 1e-12)
        assertEquals(0.085 * 1080.0, SplashTimeline.wordmarkSize(1080.0, 1920.0, phone), 1e-9)
        assertEquals(0.072 * 1080.0, SplashTimeline.wordmarkSize(1920.0, 1080.0, tv), 1e-9)
    }

    @Test
    fun beatsFollowThePreviewTimeline() {
        val off = frame(0.05)
        assertNull(off.dot)
        assertNull(off.tube)
        assertNull(off.wordmark)
        assertEquals(SplashTimeline.off.r, off.clear.r, 0.0)
        assertEquals(0.035, off.grainAlpha, 1e-9)

        val dot = frame(0.18).dot!!
        assertEquals(dot.coreH, dot.coreW, 0.01)
        assertTrue(dot.coreH in 4.0..18.0)
        assertTrue(dot.alpha > 0.5)
        assertNull(frame(0.18).tube)

        val line = frame(0.36).dot!!
        assertTrue(line.coreW > line.coreH * 8)
        assertNull(frame(0.09).dot)
        assertNull(frame(0.46).dot)

        val opening = frame(0.60).tube!!
        assertTrue(opening.h > opening.w * 0.5)
        assertTrue(opening.flash > 0.3)
        assertTrue(opening.scanAlpha > 0.05)
        assertNull(opening.prism)

        val screen = frame(0.78).tube!!
        assertEquals(4.0 / 3.0, screen.w / screen.h, 1e-6)
        assertEquals(864.0, screen.w, 0.05)
        assertEquals(648.0, screen.h, 0.05)
        assertTrue(screen.flash > 0.05)
        assertTrue(screen.flash < 0.2)

        val landed = frame(0.70).tube!!.prism!!
        assertEquals(0.70, landed.scale, 1e-9)
        assertEquals(0.0, landed.alpha, 1e-9)
        assertNull(frame(0.69).tube!!.prism)

        var peak = 0.0
        var t = 0.70
        while (t <= 1.20) {
            peak = maxOf(peak, frame(t).tube!!.prism!!.scale)
            t += 0.005
        }
        // The preview's damped cosine (exp(-8.5t) cos(15.5t)) peaks just over 105%, then settles at 100%.
        assertTrue("spring peaks at $peak", peak in 1.05..1.07)

        val settled = frame(1.20).tube!!.prism!!
        assertEquals(1.0, settled.scale, 0.01)
        assertEquals(1.0, settled.alpha, 1e-6)
        assertTrue(settled.glow > 0.4)
        assertEquals(0.0, frame(1.70).tube!!.prism!!.glow, 1e-9)

        val warm = frame(0.50).tube!!
        assertEquals(118.2, warm.flashColor.r, 0.05)
        assertEquals(207.9, warm.flashColor.g, 0.05)
        assertEquals(248.4, warm.flashColor.b, 0.05)
        assertTrue(warm.flashColor.r < 180.0)

        val earlyBand = frame(0.70).tube!!.bandY
        val lateBand = frame(1.10).tube!!.bandY
        assertTrue(lateBand > earlyBand + 200.0)

        assertNull(frame(1.47).wordmark)
        val rising = frame(1.60).wordmark!!
        val held = frame(1.90).wordmark!!
        assertTrue(held.baseline < rising.baseline)
        assertTrue(held.letterSpacingPx < rising.letterSpacingPx)
        assertEquals(1.0, held.alpha, 1e-9)
        assertTrue(rising.alpha in 0.15..0.7)
        assertEquals("Yarmiplay", held.name)
        assertEquals("TV", held.tv)
        assertEquals(SplashTimeline.wordmarkName.r, held.nameColor.r, 0.0)
        assertEquals(SplashTimeline.accent.b, held.tvColor.b, 0.0)

        val end = frame(SplashTimeline.DURATION_SEC)
        val tube = end.tube!!
        assertTrue(tube.x < 0.0 && tube.y < 0.0)
        assertTrue(tube.x + tube.w > 1080.0)
        assertTrue(tube.y + tube.h > 1920.0)
        assertEquals(SplashTimeline.background.r, tube.faceCenter.r, 0.01)
        assertEquals(SplashTimeline.background.g, tube.faceCenter.g, 0.01)
        assertEquals(SplashTimeline.background.b, tube.faceCenter.b, 0.01)
        assertEquals(0.0, tube.edge, 1e-9)
        assertEquals(1.0, tube.prism!!.scale, 1e-3)
        assertEquals(0.0, tube.prism.glow, 1e-9)
        assertEquals(1.0, end.wordmark!!.alpha, 1e-9)
        assertEquals(0.0, end.grainAlpha, 1e-9)
        assertEquals(end.wordmark.baseline, frame(2.0).wordmark!!.baseline, 1e-9)
    }

    @Test
    fun flickerDimsThePrismWhileTheTubeWarmsUp() {
        assertEquals(0.93, SplashTimeline.flicker[3], 0.0)
        val prism = frame(0.71).tube!!.prism!!
        assertTrue(prism.alpha > 0.15)
        assertTrue(prism.alpha < 0.22)
    }

    @Test
    fun prismGeometryIsTheRealLogo() {
        val xml = repoFile("shared/src/androidMain/res/drawable/ic_logo.xml").readText()
        assertTrue(xml.contains("M 50.30,54.00 L 81.48,54.00 L 36.48,81.64"))
        assertTrue(xml.contains("M 36.48,26.36 L 50.30,54.00 L 36.48,81.64"))
        assertTrue(xml.contains("M 36.48,26.36 L 81.48,54.00 L 50.30,54.00"))
        assertTrue(xml.contains("#FF0284C7"))
        assertTrue(xml.contains("#FF0369A1"))
        assertTrue(xml.contains("#FF075985"))
        assertTrue(xml.contains("#FF38BDF8"))
        assertTrue(xml.contains("#FF0EA5E9"))
        assertTrue(xml.contains("#FFA5F3FC"))
        assertTrue(xml.contains("#FF67E8F9"))
        assertEquals(50.30, PrismLogo.m.x, 1e-9)
        assertEquals(54.0, PrismLogo.m.y, 1e-9)
        assertEquals(81.64, PrismLogo.b.y, 1e-9)
        assertEquals(26.36, PrismLogo.a.y, 1e-9)
        assertEquals(81.48, PrismLogo.c.x, 1e-9)
        assertEquals(PrismLogo.b.y - PrismLogo.a.y, PrismLogo.HEIGHT_UNITS, 1e-9)
        assertEquals(56.0, PrismLogo.OPTICAL_X, 0.0)
        assertEquals(0x0284C7, PrismLogo.bottom.stops[0].second)
        assertEquals(0xA5F3FC, PrismLogo.top.stops[0].second)
        assertEquals(0.38, PrismLogo.creases[0].width, 0.0)
        assertEquals(0.80, PrismLogo.creases[0].alpha, 0.0)
        assertTrue(xml.contains("strokeWidth=\"0.38\""))
        assertTrue(xml.contains("strokeWidth=\"0.32\""))
        assertTrue(xml.contains("strokeWidth=\"0.26\""))
    }

    @Test
    fun interIsBundledWithItsLicense() {
        val font = repoFile("app/src/main/res/font/inter_semibold.ttf").readBytes()
        assertTrue(font.size > 100_000)
        assertEquals(0x00, font[0].toInt() and 0xFF)
        assertEquals(0x01, font[1].toInt() and 0xFF)
        assertEquals(0x00, font[2].toInt() and 0xFF)
        assertEquals(0x00, font[3].toInt() and 0xFF)
        val license = repoFile("app/src/main/assets/licenses/Inter-LICENSE.txt").readText()
        assertTrue(license.contains("SIL Open Font License"))
        assertTrue(license.contains("Copyright (c) 2016 The Inter Project Authors"))
        assertTrue(license.contains("https://github.com/rsms/inter"))
    }

    private fun frame(t: Double) = SplashTimeline.frame(t, 1080.0, 1920.0, phone)

    private fun repoFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOfNotNull(File(cwd, relative), File(cwd, "app/$relative"), cwd.parentFile?.let { File(it, relative) })
        return candidates.firstOrNull { it.isFile } ?: error("missing $relative from $cwd")
    }
}
