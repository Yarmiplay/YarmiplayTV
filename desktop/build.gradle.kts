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
}

val appVersion = "1.0.0"
val libmpvDir = project(":player-mpv-desktop").layout.buildDirectory.dir("libmpv")

compose.desktop {
    application {
        mainClass = "com.syncplaytv.desktop.MainKt"
        jvmArgs += listOf(
            "-Dsyncplaytv.version=$appVersion",
            "-Dsyncplaytv.libmpv.dir=${libmpvDir.get().asFile.absolutePath}",
        )
    }
}

afterEvaluate {
    tasks.named("run") { dependsOn(":player-mpv-desktop:prepareLibmpv") }
}
