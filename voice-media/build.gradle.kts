import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

val os: OperatingSystem = OperatingSystem.current()
val arch: String = System.getProperty("os.arch").lowercase()
val isArm64: Boolean = arch == "aarch64" || arch == "arm64"
val webrtcClassifier: String = when {
    os.isWindows -> if (isArm64) "windows-aarch64" else "windows-x86_64"
    os.isMacOsX -> if (isArm64) "macos-aarch64" else "macos-x86_64"
    else -> if (isArm64) "linux-aarch64" else "linux-x86_64"
}

val deepFilterModel: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    implementation(project(":syncplay-protocol"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.concentus)
    implementation(libs.okhttp)
    compileOnly(libs.webrtc.java)
    compileOnly(libs.onnxruntime)
    deepFilterModel(libs.android.deepfilternet)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.webrtc.java)
    testRuntimeOnly(variantOf(libs.webrtc.java) { classifier(webrtcClassifier) })
    testImplementation(libs.onnxruntime)
}

tasks.named<ProcessResources>("processTestResources") {
    dependsOn(deepFilterModel)
    from({
        zipTree(deepFilterModel.singleFile).matching {
            include("res/raw/deep_filter_mobile_model")
        }
    }) {
        eachFile {
            path = "deepfilternet3_onnx.tar.gz"
        }
        includeEmptyDirs = false
    }
}

tasks.test {
    maxParallelForks = 1
    environment("SYNCPLAY_TEST_SERVER", System.getenv("SYNCPLAY_TEST_SERVER") ?: "")
    testLogging { events("passed", "skipped", "failed", "standardOut", "standardError") }
}
