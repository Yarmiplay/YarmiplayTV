package com.yarmiplaytv.player.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Preview frames of local videos for the file lists and the seek bar: a short-lived libmpv instance of its own,
 * separate from the player's, decodes one frame (a tenth of the way in, unless told where) and writes it as a JPEG
 * with mpv's image output.
 */
object MpvFrameGrabber {
    private const val TIMEOUT_SECONDS = 20L

    /**
     * Writes a frame of the video at [path], [width] pixels wide, to [target]; false when mpv can't decode one.
     * [start] is where, as mpv's `start` option takes it (seconds, or a percentage).
     */
    fun grab(path: String, width: Int, target: File, start: String = "10%"): Boolean {
        val outDir = Files.createTempDirectory("yarmiplaytv-frame").toFile()
        try {
            val core = MpvCore(options(width, outDir, start), logLevel = "no")
            core.use {
                val done = CountDownLatch(1)
                core.startEvents { event -> if (event is MpvEvent.EndFile || event == MpvEvent.Shutdown) done.countDown() }
                if (core.command("loadfile", path) < 0) return false
                done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            val frame = outDir.listFiles { file -> file.extension == "jpg" && file.length() > 0 }?.minByOrNull { it.name } ?: return false
            Files.move(frame.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return true
        } finally {
            outDir.deleteRecursively()
        }
    }

    private fun options(width: Int, outDir: File, start: String) = mapOf(
        "vo" to "image",
        "vo-image-format" to "jpg",
        "vo-image-jpeg-quality" to "85",
        "vo-image-outdir" to outDir.absolutePath,
        // By default past the black frames and logos most videos open with; the keyframe before is close enough.
        "start" to start,
        "hr-seek" to "no",
        "frames" to "1",
        "vf" to "scale=w=$width:h=-2",
        "untimed" to "yes",
        "aid" to "no",
        "sid" to "no",
        "ao" to "null",
        "hwdec" to "no",
        "keep-open" to "no",
        "load-scripts" to "no",
        "ytdl" to "no",
        "osd-level" to "0",
    )
}
