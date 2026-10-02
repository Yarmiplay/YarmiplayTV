plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(17)

    androidTarget()
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(project(":syncplay-protocol"))
            api(project(":media-source"))
            api(project(":player-api"))
            api(libs.kotlinx.coroutines.core)
            api(libs.androidx.datastore.preferences.core)

            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.ui)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.compose.material.icons)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.coil.compose)
        }
        androidMain.dependencies {
            implementation(project(":player-mpv"))
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }
        val desktopMain by getting {
            // The logo is one Android vector drawable, read on desktop with loadXmlImageVector.
            resources.srcDir("src/androidMain/res/drawable")
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

android {
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

tasks.named<Test>("desktopTest") {
    testLogging {
        events("passed", "skipped", "failed")
    }
}
