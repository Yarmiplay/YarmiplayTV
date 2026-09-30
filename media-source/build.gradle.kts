plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    // Live tests against a Jellyfin server run only when JELLYFIN_URL and JELLYFIN_TOKEN are set.
    environment("JELLYFIN_URL", System.getenv("JELLYFIN_URL") ?: "")
    environment("JELLYFIN_TOKEN", System.getenv("JELLYFIN_TOKEN") ?: "")
    environment("JELLYFIN_USER_ID", System.getenv("JELLYFIN_USER_ID") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
