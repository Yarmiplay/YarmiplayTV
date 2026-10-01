plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
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
    namespace = "com.syncplaytv.shared"
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
