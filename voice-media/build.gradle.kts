plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":syncplay-protocol"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.concentus)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    testLogging {
        events("passed", "skipped", "failed")
    }
}
