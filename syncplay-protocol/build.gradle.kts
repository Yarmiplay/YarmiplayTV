plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // Integration tests against a real syncplay-server run only when SYNCPLAY_TEST_SERVER=host:port is set.
    environment("SYNCPLAY_TEST_SERVER", System.getenv("SYNCPLAY_TEST_SERVER") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
