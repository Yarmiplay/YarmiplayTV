import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.yarmiplaytv.player"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

// dev.jdtech.mpv:libmpv 1.0.0 builds FFmpeg with --disable-filters, so showfreqs and showspectrum
// are absent and a lavfi spectrum cannot run. The prebuilt libraries are that same FFmpeg 8.1
// libavfilter rebuilt with the desktop graph's filters (scripts/build-android-libavfilter.sh).
// The rest of the AAR (libmpv and the other FFmpeg libraries) is unchanged.
val upstreamLibmpv: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

val filterAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

abstract class PatchLibmpvAarTask : DefaultTask() {
    @get:InputFile
    abstract val upstreamAar: RegularFileProperty

    @get:InputFiles
    abstract val filterLibs: ConfigurableFileCollection

    @get:OutputFile
    abstract val patchedAar: RegularFileProperty

    @TaskAction
    fun patch() {
        val out = patchedAar.get().asFile
        out.parentFile.mkdirs()
        val replacements = filterLibs.files.associateBy { file ->
            "jni/${file.parentFile.name}/libavfilter.so"
        }
        replacements.values.forEach { file ->
            val text = file.readBytes().toString(Charsets.ISO_8859_1)
            listOf("showfreqs", "showspectrum", "asplit", "aresample").forEach { name ->
                check(text.contains("$name\u0000")) { "$file does not contain the $name filter" }
            }
        }
        val replaced = HashSet<String>()
        ZipFile(upstreamAar.get().asFile).use { zip ->
            ZipOutputStream(FileOutputStream(out)).use { output ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val replacement = replacements[entry.name]
                    val next = ZipEntry(entry.name)
                    next.time = entry.time
                    output.putNextEntry(next)
                    if (replacement != null) {
                        replaced += entry.name
                        replacement.inputStream().use { it.copyTo(output) }
                    } else {
                        zip.getInputStream(entry).use { it.copyTo(output) }
                    }
                    output.closeEntry()
                }
            }
        }
        check(replaced == replacements.keys) { "AAR is missing ${replacements.keys - replaced}" }
    }
}

val patchLibmpvAar = tasks.register<PatchLibmpvAarTask>("patchLibmpvAar") {
    upstreamAar.set(layout.file(upstreamLibmpv.incoming.files.elements.map { files ->
        files.map { it.asFile }.single { it.extension == "aar" }
    }))
    filterLibs.setFrom(filterAbis.map { abi ->
        layout.projectDirectory.file("src/main/prebuilt-libavfilter/$abi/libavfilter.so")
    })
    patchedAar.set(layout.buildDirectory.file("libmpv/libmpv-visualizer.aar"))
}

dependencies {
    add(upstreamLibmpv.name, libs.libmpv)
    api(project(":player-api"))
    api(files(patchLibmpvAar))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
