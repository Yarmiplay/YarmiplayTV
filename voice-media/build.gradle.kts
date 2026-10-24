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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    testLogging {
        events("passed", "skipped", "failed")
    }
}
