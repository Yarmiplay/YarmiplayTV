package com.yarmiplaytv.support

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import com.yarmiplaytv.DeviceKind
import com.yarmiplaytv.DeviceUi
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Full-screen reference screenshots (`app/src/androidTest/screenshots/<device>/<name>.png`).
 *
 * The screen is captured with UiAutomation, so dialogs, bottom sheets and the video surface are
 * included. The status bar is frozen with System UI demo mode (fixed clock, battery and signal).
 *
 * Run with `-e recordScreenshots true` to write new references instead of comparing; they land in
 * the app's external files dir (`screenshots/record/<device>`) for `scripts/android-safety-net.ps1`
 * to pull. A mismatch saves the actual image and a diff (differing pixels in red) under
 * `screenshots/failures/<device>`.
 */
object Screenshots {
    private const val TAG = "Screenshots"

    /** Per-channel difference treated as rendering noise. */
    private const val CHANNEL_TOLERANCE = 3

    /** Share of pixels allowed to exceed the tolerance (anti-aliasing at glyph edges). */
    private const val MAX_DIFF_FRACTION = 0.0001

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation: UiAutomation get() = instrumentation.uiAutomation

    val recording: Boolean
        get() = InstrumentationRegistry.getArguments().getString("recordScreenshots") == "true"

    /**
     * The references match the safety net's emulators (scripts/android-safety-net.ps1 passes
     * `-e screenshots true`); other devices, like CI's software-rendered emulator, skip the comparison.
     */
    val enabled: Boolean
        get() = recording || InstrumentationRegistry.getArguments().getString("screenshots") == "true"

    /** E.g. `phone-1080x2400`: device kind plus the display's natural (portrait for phones) pixel size. */
    val device: String by lazy {
        val context = instrumentation.targetContext
        val kind = DeviceUi.kind(context)
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val (short, long) = minOf(bounds.width(), bounds.height()) to maxOf(bounds.width(), bounds.height())
        val size = if (kind == DeviceKind.TV) "${long}x$short" else "${short}x$long"
        "${kind.name.lowercase()}-$size"
    }

    private val outDir: File get() = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")

    private val failures = mutableListOf<String>()

    /**
     * Lets the UI catch up before each capture. Under a Compose test rule the UI only recomposes while
     * the test waits for it, so tests set this to the rule's `waitForIdle`.
     */
    var idle: () -> Unit = {}

    fun shell(command: String): String {
        val pfd: ParcelFileDescriptor = automation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }

    /** Fixed status bar, no animations, no rotation surprises. Undo with [restoreDevice]. */
    fun prepareDevice() {
        listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale").forEach { shell("settings put global $it 0") }
        shell("settings put global sysui_demo_allowed 1")
        enterDemo()
        // Right after a boot System UI can drop the network command while its Wi-Fi state is still loading, or
        // show a second Wi-Fi icon; a fresh demo session once it has settled shows exactly one.
        Thread.sleep(1_000)
        demo("exit")
        enterDemo()
        automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
    }

    private fun enterDemo() {
        demo("enter")
        demo("clock -e hhmm 1200")
        demo("battery -e level 100 -e plugged false -e powersave false")
        demo("network -e wifi show -e level 4 -e fully true -e mobile hide -e airplane hide")
        demo("notifications -e visible false")
        demo("status -e volume hide -e bluetooth hide -e location hide -e alarm hide -e sync hide -e mute hide -e speakerphone hide")
    }

    fun restoreDevice() {
        demo("exit")
        listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale").forEach { shell("settings put global $it 1") }
        automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    private fun demo(command: String) {
        val parts = command.split(" ", limit = 2)
        shell("am broadcast -a com.android.systemui.demo -e command ${parts[0]}${if (parts.size > 1) " " + parts[1] else ""}")
    }

    /**
     * Turns the display a quarter (landscape on phones) or back to its natural orientation, and waits
     * until the rotation is applied and the UI is idle again.
     */
    fun rotate(landscape: Boolean) {
        val display = instrumentation.targetContext.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val target = if (landscape) Surface.ROTATION_90 else Surface.ROTATION_0
        val turning = display.rotation != target
        automation.setRotation(if (landscape) UiAutomation.ROTATION_FREEZE_90 else UiAutomation.ROTATION_FREEZE_0)
        if (!turning) return
        AppState.waitUntil(5_000) { display.rotation == target }
        Thread.sleep(1_000)
        instrumentation.waitForIdleSync()
    }

    /**
     * Compares the screen with the reference [name], retrying for a few seconds so late image loads
     * or layout passes can finish. Mismatches are collected; call [assertAllMatched] at the end of a test.
     * Pixels inside [ignore] (fractions of the screen size) aren't compared.
     */
    fun check(name: String, timeoutMs: Long = 6_000, ignore: List<RectF> = emptyList()) {
        if (recording) {
            Thread.sleep(1_500)
            val shot = capture()
            // Two identical captures in a row, so a still-settling screen isn't recorded.
            var previous = shot
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                Thread.sleep(500)
                val next = capture()
                if (diff(previous, next).count == 0 || System.currentTimeMillis() > deadline) {
                    save(next, File(outDir, "record/$device/$name.png"))
                    Log.i(TAG, "Recorded $device/$name")
                    return
                }
                previous = next
            }
        }
        val reference = loadReference(name) ?: run {
            val actual = capture()
            save(actual, File(outDir, "failures/$device/$name.actual.png"))
            failures += "$name: no reference for $device (record with -e recordScreenshots true)"
            return
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Bitmap
        var result: Diff
        do {
            Thread.sleep(300)
            last = capture()
            result = diff(reference, last, ignore)
            if (result.matches(reference)) return
        } while (System.currentTimeMillis() < deadline)
        save(last, File(outDir, "failures/$device/$name.actual.png"))
        result.image?.let { save(it, File(outDir, "failures/$device/$name.diff.png")) }
        failures += "$name: ${result.describe(reference)}"
        Log.e(TAG, "Mismatch $device/$name: ${result.describe(reference)}")
    }

    /** Saves the current screen next to the screenshot failures, for diagnosing other test failures. */
    fun saveFailure(name: String): String {
        val file = File(outDir, "failures/$device/$name.png")
        runCatching { save(capture(), file) }
        return "failures/$device/$name.png"
    }

    fun assertAllMatched() {
        val list = failures.toList()
        failures.clear()
        if (list.isNotEmpty()) throw AssertionError("Screenshots differ on $device:\n" + list.joinToString("\n"))
    }

    fun capture(): Bitmap {
        idle()
        instrumentation.waitForIdleSync()
        return requireNotNull(automation.takeScreenshot()) { "takeScreenshot failed" }.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun loadReference(name: String): Bitmap? = runCatching {
        instrumentation.context.assets.open("$device/$name.png").use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun save(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    class Diff(val count: Int, val sizeMismatch: Boolean, val image: Bitmap?) {
        fun matches(reference: Bitmap) = !sizeMismatch && count <= reference.width * reference.height * MAX_DIFF_FRACTION
        fun describe(reference: Bitmap) =
            if (sizeMismatch) "size differs from the reference ${reference.width}x${reference.height}"
            else "$count pixels differ (allowed ${(reference.width * reference.height * MAX_DIFF_FRACTION).toInt()})"
    }

    fun diff(a: Bitmap, b: Bitmap, ignore: List<RectF> = emptyList()): Diff {
        if (a.width != b.width || a.height != b.height) return Diff(Int.MAX_VALUE, true, null)
        val w = a.width
        val h = a.height
        val pa = IntArray(w * h).also { a.getPixels(it, 0, w, 0, 0, w, h) }
        val pb = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val skipped = ignore.map { Rect((it.left * w).toInt(), (it.top * h).toInt(), (it.right * w).toInt(), (it.bottom * h).toInt()) }
        var count = 0
        val out = IntArray(w * h)
        for (i in pa.indices) {
            val x = pa[i]
            val y = pb[i]
            val differs = x != y && skipped.none { it.contains(i % w, i / w) } && (
                abs(Color.red(x) - Color.red(y)) > CHANNEL_TOLERANCE ||
                    abs(Color.green(x) - Color.green(y)) > CHANNEL_TOLERANCE ||
                    abs(Color.blue(x) - Color.blue(y)) > CHANNEL_TOLERANCE
                )
            if (differs) {
                count++
                out[i] = Color.RED
            } else {
                // The actual image, dimmed, for context.
                out[i] = Color.rgb(Color.red(y) / 4, Color.green(y) / 4, Color.blue(y) / 4)
            }
        }
        val image = if (count > 0) Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888) else null
        return Diff(count, false, image)
    }
}
