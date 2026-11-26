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

    // Built from official upstream sources (jarnedemeulemeester/libmpv-android v1.1.0, mpv 0.42.0,
    // FFmpeg 9.1) by scripts/build-android-libmpv.sh with the visualizer filters enabled and
    // Android AudioTrack raw bitstream passthrough (AC3/E-AC3/DTS), multichannel IEC 61937,
    // route-change AO reload, and passthrough playhead smoothing (scripts/patches/mpv/010*.patch).
    sourceSets["main"].jniLibs.srcDir("src/main/prebuilt-libmpv/jni")
}

kotlin {
    jvmToolchain(17)
}

val prebuiltAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

abstract class VerifyPrebuiltLibmpvTask : DefaultTask() {
    @get:InputFiles
    abstract val mpvLibs: ConfigurableFileCollection

    @get:InputFiles
    abstract val filterLibs: ConfigurableFileCollection

    @get:InputFiles
    abstract val utilLibs: ConfigurableFileCollection

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun verify() {
        mpvLibs.files.forEach { file ->
            check(file.isFile) { "Missing $file; run scripts/build-android-libmpv.sh" }
            val text = file.readBytes().toString(Charsets.ISO_8859_1)
            listOf("0.42.0", "raw-passthrough", "route-reload", "using raw passthrough").forEach { marker ->
                check(text.contains(marker)) { "$file is missing the $marker implementation" }
            }
        }
        filterLibs.files.forEach { file ->
            check(file.isFile) { "Missing $file; run scripts/build-android-libmpv.sh" }
            val text = file.readBytes().toString(Charsets.ISO_8859_1)
            listOf("showfreqs", "showspectrum", "asplit", "aresample").forEach { name ->
                check(text.contains("$name\u0000")) { "$file does not contain the $name filter" }
            }
        }
        utilLibs.files.forEach { file ->
            check(file.isFile) { "Missing $file; run scripts/build-android-libmpv.sh" }
            val text = file.readBytes().toString(Charsets.ISO_8859_1)
            check(text.contains("9.1")) { "$file does not look like FFmpeg 9.1" }
        }
        stamp.get().asFile.apply {
            parentFile.mkdirs()
            writeText("ok\n")
        }
    }
}

val verifyPrebuiltLibmpv = tasks.register<VerifyPrebuiltLibmpvTask>("verifyPrebuiltLibmpv") {
    mpvLibs.setFrom(prebuiltAbis.map { layout.projectDirectory.file("src/main/prebuilt-libmpv/jni/$it/libmpv.so") })
    filterLibs.setFrom(prebuiltAbis.map { layout.projectDirectory.file("src/main/prebuilt-libmpv/jni/$it/libavfilter.so") })
    utilLibs.setFrom(prebuiltAbis.map { layout.projectDirectory.file("src/main/prebuilt-libmpv/jni/$it/libavutil.so") })
    stamp.set(layout.buildDirectory.file("libmpv/verified.txt"))
}

tasks.named("preBuild") { dependsOn(verifyPrebuiltLibmpv) }

dependencies {
    api(project(":player-api"))
    api(files("src/main/prebuilt-libmpv/libmpv-classes.jar"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
