import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)
}

val lwjglNatives = listOf("natives-windows", "natives-linux", "natives-macos", "natives-macos-arm64")

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.jna)
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.glfw)
    implementation(libs.lwjgl.opengl)
    lwjglNatives.forEach { classifier ->
        runtimeOnly(variantOf(libs.lwjgl) { classifier(classifier) })
        runtimeOnly(variantOf(libs.lwjgl.glfw) { classifier(classifier) })
        runtimeOnly(variantOf(libs.lwjgl.opengl) { classifier(classifier) })
    }
    implementation(compose.desktop.common)

    testImplementation(libs.junit)
    testImplementation(compose.desktop.currentOs)
}

// --- libmpv for Windows -------------------------------------------------------------------------
// macOS and Linux use the system libmpv (Homebrew / distro package). On Windows we bundle a pinned
// shinchiro build; its .7z uses the BCJ2 filter, which only 7-Zip itself can unpack.

object Libmpv {
    const val TAG = "20261001"
    const val ARCHIVE = "mpv-dev-x86_64-20261001-git-3186d369f9.7z"
    const val ARCHIVE_SHA256 = "DE0AA24A39E27B07D9F663616F8D09C91586C7328D8C33FBFBC8E3888C35D89C"
    const val ARCHIVE_URL = "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/$TAG/$ARCHIVE"
    const val SEVEN_ZIP_URL = "https://github.com/ip7z/7zip/releases/download/26.03/7zr.exe"
    const val SEVEN_ZIP_SHA256 = "AD4C82FADCBDF93C03B4FC440F300509C7D60C5C2F4D183E35D9D70D6957037D"
}

val isWindows = System.getProperty("os.name").startsWith("Windows")
val libmpvDir = layout.buildDirectory.dir("libmpv")

val prepareLibmpv by tasks.registering {
    description = "Downloads the pinned libmpv-2.dll (Windows only)."
    onlyIf { isWindows }
    val cache = File(gradle.gradleUserHomeDir, "caches/syncplaytv-libmpv/${Libmpv.TAG}")
    val outDir = libmpvDir
    inputs.property("archive", Libmpv.ARCHIVE_SHA256)
    outputs.dir(outDir)
    doLast {
        fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02X".format(it) }
        fun fetch(url: String, target: File, sha: String) {
            if (target.isFile && sha256(target) == sha) return
            target.parentFile.mkdirs()
            logger.lifecycle("Downloading $url")
            URI(url).toURL().openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            val actual = sha256(target)
            check(actual == sha) { "Checksum mismatch for $url: $actual" }
        }
        val archive = File(cache, Libmpv.ARCHIVE)
        val sevenZip = File(cache, "7zr.exe")
        fetch(Libmpv.ARCHIVE_URL, archive, Libmpv.ARCHIVE_SHA256)
        fetch(Libmpv.SEVEN_ZIP_URL, sevenZip, Libmpv.SEVEN_ZIP_SHA256)
        val dir = outDir.get().asFile
        dir.mkdirs()
        val process = ProcessBuilder(sevenZip.absolutePath, "e", "-y", "-o${dir.absolutePath}", archive.absolutePath, "libmpv-2.dll")
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "7zr failed:\n$output" }
        check(File(dir, "libmpv-2.dll").isFile) { "libmpv-2.dll missing after extraction" }
    }
}

tasks.test {
    dependsOn(prepareLibmpv)
    systemProperty("jna.library.path", libmpvDir.get().asFile.absolutePath)
    // The integration tests need a display and libmpv; set SYNCPLAYTV_SKIP_MPV_TESTS=1 to skip them.
    environment("SYNCPLAYTV_SKIP_MPV_TESTS", System.getenv("SYNCPLAYTV_SKIP_MPV_TESTS") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
