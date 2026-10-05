plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose)
}

// settings.gradle.kts leaves out the Android modules with -PdesktopOnly; this module then has no Android target.
val android = !providers.gradleProperty("desktopOnly").isPresent
if (android) apply(plugin = libs.plugins.android.library.get().pluginId)

kotlin {
    jvmToolchain(17)

    if (android) androidTarget()
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(project(":syncplay-protocol"))
            api(project(":media-source"))
            api(project(":player-api"))
            api(libs.kotlinx.coroutines.core)
            api(libs.androidx.datastore.preferences.core)
            implementation(libs.kotlinx.serialization.json)

            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.ui)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.compose.material.icons)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.coil.compose)
        }
        if (android) {
            androidMain.dependencies {
                implementation(project(":player-mpv"))
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
            }
        }
        val desktopMain by getting {
            // The logo is one Android vector drawable, read on desktop with loadXmlImageVector.
            resources.srcDir("src/androidMain/res/drawable")
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.okhttp.mockwebserver)
            }
        }
    }
}

if (android) {
    extensions.configure<com.android.build.api.dsl.LibraryExtension> {
        namespace = "com.yarmiplaytv.shared"
        compileSdk = 36

        defaultConfig {
            minSdk = 26
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

tasks.named<Test>("desktopTest") {
    testLogging {
        events("passed", "skipped", "failed")
    }
}
