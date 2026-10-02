import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":player-mpv-desktop"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.jetbrains.lifecycle.runtime.compose)
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

tasks.test {
    dependsOn(":player-mpv-desktop:prepareLibmpv")
    val screenshots = layout.buildDirectory.dir("desktop-screenshots").get().asFile.absolutePath
    systemProperty("syncplaytv.screenshotDir", screenshots)
    systemProperty("syncplaytv.libmpv.dir", libmpvDir.get().asFile.absolutePath)
    systemProperty("syncplaytv.syncClip", rootProject.file("app/src/androidTest/assets/syncplaytv-sync-clip.mp4").absolutePath)
    environment("SYNCPLAY_TEST_SERVER", System.getenv("SYNCPLAY_TEST_SERVER") ?: "")
    environment("SYNCPLAY_E2E_ROOM", System.getenv("SYNCPLAY_E2E_ROOM") ?: "")
    testLogging { events("passed", "skipped", "failed") }
}

val appVersion = "1.0.0"
val libmpvDir = project(":player-mpv-desktop").layout.buildDirectory.dir("libmpv")

/** Draws the app logo (the two play triangles of ic_logo.xml on the banner gradient) as png, ico and icns. */
abstract class GenerateAppIcons : DefaultTask() {
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val dir = outputDir.get().asFile.apply { mkdirs() }
        dir.resolve("icon.png").writeBytes(png(512))
        dir.resolve("icon.ico").writeBytes(ico(listOf(16, 24, 32, 48, 64, 256)))
        dir.resolve("icon.icns").writeBytes(
            icns(listOf("icp4" to 16, "icp5" to 32, "icp6" to 64, "ic07" to 128, "ic08" to 256, "ic09" to 512, "ic10" to 1024)),
        )
    }

    private fun render(size: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val margin = size * 0.06
        val side = size - 2 * margin
        val tile = RoundRectangle2D.Double(margin, margin, side, side, side * 0.44, side * 0.44)
        g.paint = GradientPaint(0f, 0f, Color(0x1B2A41), size.toFloat(), size.toFloat(), Color(0x0E1116))
        g.fill(tile)
        g.paint = Color(255, 255, 255, 28)
        g.stroke = BasicStroke((size / 128.0).coerceAtLeast(1.0).toFloat())
        g.draw(tile)
        // ic_logo.xml uses a 108-unit viewport centred on (54, 54).
        val scale = size / 108.0 * 1.3
        g.translate(size / 2.0, size / 2.0)
        g.scale(scale, scale)
        g.translate(-54.0, -54.0)
        fun triangle(vararg points: Double) = Path2D.Double().apply {
            moveTo(points[0], points[1]); lineTo(points[2], points[3]); lineTo(points[4], points[5]); closePath()
        }
        g.paint = Color(0x3D, 0xA5, 0xF4)
        g.fill(triangle(30.0, 28.0, 30.0, 80.0, 70.0, 54.0))
        g.paint = Color(255, 255, 255, 0xCC)
        g.fill(triangle(46.0, 34.0, 46.0, 74.0, 78.0, 54.0))
        g.dispose()
        return image
    }

    private fun png(size: Int): ByteArray = ByteArrayOutputStream().also { ImageIO.write(render(size), "png", it) }.toByteArray()

    /** An .ico with PNG payloads (supported since Windows Vista). */
    private fun ico(sizes: List<Int>): ByteArray {
        val images = sizes.map(::png)
        val out = ByteArrayOutputStream()
        fun le16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
        fun le32(v: Int) { le16(v and 0xFFFF); le16(v ushr 16) }
        le16(0); le16(1); le16(images.size)
        var offset = 6 + 16 * images.size
        sizes.zip(images).forEach { (size, data) ->
            out.write(if (size >= 256) 0 else size); out.write(if (size >= 256) 0 else size)
            out.write(0); out.write(0); le16(1); le16(32); le32(data.size); le32(offset)
            offset += data.size
        }
        images.forEach(out::write)
        return out.toByteArray()
    }

    /** An .icns with PNG payloads (supported since macOS 10.7). */
    private fun icns(entries: List<Pair<String, Int>>): ByteArray {
        val chunks = entries.map { (type, size) -> type to png(size) }
        val out = ByteArrayOutputStream()
        fun be32(v: Int) { out.write(v ushr 24); out.write(v shr 16 and 0xFF); out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
        out.write("icns".toByteArray(Charsets.US_ASCII))
        be32(8 + chunks.sumOf { 8 + it.second.size })
        chunks.forEach { (type, data) -> out.write(type.toByteArray(Charsets.US_ASCII)); be32(8 + data.size); out.write(data) }
        return out.toByteArray()
    }
}

val generateAppIcons = tasks.register<GenerateAppIcons>("generateAppIcons") {
    outputDir.set(layout.buildDirectory.dir("icons"))
}

// Installers read resources from <root>/common and <root>/<os>; Windows gets the bundled libmpv-2.dll.
val prepareDesktopAppResources = tasks.register<Sync>("prepareDesktopAppResources") {
    dependsOn(":player-mpv-desktop:prepareLibmpv")
    from(libmpvDir) {
        include("*.dll")
        into("windows")
    }
    into(layout.buildDirectory.dir("appResources"))
}

val videoTypes = listOf(
    Triple("video/x-matroska", "mkv", "Matroska video"),
    Triple("video/mp4", "mp4", "MPEG-4 video"),
    Triple("video/x-m4v", "m4v", "MPEG-4 video"),
    Triple("video/x-msvideo", "avi", "AVI video"),
    Triple("video/quicktime", "mov", "QuickTime video"),
    Triple("video/webm", "webm", "WebM video"),
    Triple("video/x-ms-wmv", "wmv", "Windows Media video"),
    Triple("video/x-flv", "flv", "Flash video"),
    Triple("video/mpeg", "mpg", "MPEG video"),
    Triple("video/ogg", "ogv", "Ogg video"),
)

compose.desktop {
    application {
        mainClass = "com.syncplaytv.desktop.MainKt"
        jvmArgs += "-Dsyncplaytv.version=$appVersion"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "SyncplayTV"
            packageVersion = appVersion
            description = "Watch videos in sync with friends on Syncplay servers"
            vendor = "Yarmiplay"
            copyright = "© 2026 Yarmiplay"
            appResourcesRootDir.set(layout.dir(prepareDesktopAppResources.map { it.destinationDir }))
            // suggestRuntimeModules, plus the elliptic-curve TLS provider that HTTPS and Syncplay TLS servers need.
            modules("java.instrument", "jdk.management", "jdk.unsupported", "jdk.crypto.ec")
            videoTypes.forEach { (mime, extension, label) -> fileAssociation(mime, extension, label) }

            windows {
                iconFile.set(generateAppIcons.flatMap { it.outputDir.file("icon.ico") })
                menuGroup = "SyncplayTV"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6F0C2B1E-4E59-4C1B-9B7E-2D1A5C3F8E41"
            }
            macOS {
                iconFile.set(generateAppIcons.flatMap { it.outputDir.file("icon.icns") })
                bundleID = "com.syncplaytv.desktop"
                dockName = "SyncplayTV"
                appCategory = "public.app-category.entertainment"
            }
            linux {
                iconFile.set(generateAppIcons.flatMap { it.outputDir.file("icon.png") })
                packageName = "syncplaytv"
                debMaintainer = "Yarmiplay@users.noreply.github.com"
                appCategory = "AudioVideo"
                menuGroup = "AudioVideo;Video;Player"
                shortcut = true
            }
        }
    }
}

// Ubuntu 24.04 / Debian 12 ship libmpv2, Ubuntu 22.04 libmpv1. Free args go into jpackage's @argfile as they
// are, so a value with spaces needs its own quotes.
tasks.withType<AbstractJPackageTask>().configureEach {
    if (targetFormat == TargetFormat.Deb) freeArgs.addAll("--linux-package-deps", "\"libmpv2 | libmpv1\"")
}

afterEvaluate {
    val libmpvPath = libmpvDir.get().asFile.absolutePath
    tasks.named<JavaExec>("run") {
        dependsOn(":player-mpv-desktop:prepareLibmpv")
        systemProperty("syncplaytv.libmpv.dir", libmpvPath)
    }
}
