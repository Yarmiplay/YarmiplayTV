import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val appVersion = "1.7.1"

/** Play needs an ever-increasing versionCode: major * 10000 + minor * 100 + patch. */
fun versionCodeOf(name: String): Int {
    val parts = name.split(".").map(String::toInt)
    return parts[0] * 10000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }
}

/**
 * The Play upload key, from keystore.properties at the repository root (gitignored) or, in CI, from
 * YARMIPLAYTV_KEYSTORE, YARMIPLAYTV_KEYSTORE_PASSWORD, YARMIPLAYTV_KEY_ALIAS and YARMIPLAYTV_KEY_PASSWORD.
 * Without either, release builds are unsigned.
 */
val uploadKey: Map<String, String>? = run {
    val file = rootProject.file("keystore.properties")
    val props = Properties().apply { if (file.isFile) file.inputStream().use { load(it) } }
    fun value(key: String, env: String): String? = props.getProperty(key) ?: System.getenv(env)?.takeIf { it.isNotBlank() }
    val storeFile = value("storeFile", "YARMIPLAYTV_KEYSTORE") ?: return@run null
    mapOf(
        "storeFile" to storeFile,
        "storePassword" to value("storePassword", "YARMIPLAYTV_KEYSTORE_PASSWORD").orEmpty(),
        "keyAlias" to value("keyAlias", "YARMIPLAYTV_KEY_ALIAS").orEmpty(),
        "keyPassword" to value("keyPassword", "YARMIPLAYTV_KEY_PASSWORD").orEmpty(),
    )
}

/**
 * The download page's APK key, from YARMIPLAYTV_APK_KEYSTORE and YARMIPLAYTV_APK_KEYSTORE_PASSWORD (CI on main).
 * Signing every published debug APK with the same key lets new versions install over old ones; without it,
 * debug builds use the machine's debug key.
 */
val apkKey: Map<String, String>? = run {
    val storeFile = System.getenv("YARMIPLAYTV_APK_KEYSTORE")?.takeIf { it.isNotBlank() } ?: return@run null
    mapOf("storeFile" to storeFile, "password" to System.getenv("YARMIPLAYTV_APK_KEYSTORE_PASSWORD").orEmpty())
}

android {
    namespace = "com.yarmiplaytv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yarmiplaytv"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
        testInstrumentationRunner = "com.yarmiplaytv.YarmiplayTestRunner"
        buildConfigField("boolean", "UPDATE_CHECK", "true")
    }

    signingConfigs {
        if (uploadKey != null) {
            create("release") {
                storeFile = rootProject.file(uploadKey.getValue("storeFile"))
                storePassword = uploadKey.getValue("storePassword")
                keyAlias = uploadKey.getValue("keyAlias")
                keyPassword = uploadKey.getValue("keyPassword")
            }
        }
        if (apkKey != null) {
            create("downloadPage") {
                storeFile = file(apkKey.getValue("storeFile"))
                storePassword = apkKey.getValue("password")
                keyAlias = "yarmiplaytv-apk"
                keyPassword = apkKey.getValue("password")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("downloadPage")?.let { signingConfig = it }
        }
        release {
            // Play builds: apps on Play mustn't update themselves or point to updates outside Play.
            buildConfigField("boolean", "UPDATE_CHECK", "false")
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Reference screenshots for the safety-net comparison tests, read back from the test APK's assets.
    sourceSets["androidTest"].assets.srcDir("src/androidTest/screenshots")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":syncplay-protocol"))
    implementation(project(":media-source"))
    implementation(project(":player-mpv"))
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons)
    implementation(libs.tv.material)
    implementation(libs.compose.material3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.compose.ui.test.manifest)
}
