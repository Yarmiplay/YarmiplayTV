plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

/**
 * Tests that join rooms on the real Syncplay server (SYNCPLAY_TEST_SERVER) take turns. Syncplay tells every client
 * about users joining and leaving other rooms, and on a busy CI runner one module's rooms changing at the wrong
 * moment upset another module's timing-sensitive sync test.
 */
abstract class SyncplayTestServer : BuildService<BuildServiceParameters.None>

val syncplayTestServer = gradle.sharedServices.registerIfAbsent("syncplayTestServer", SyncplayTestServer::class) {
    maxParallelUsages.set(1)
}

subprojects {
    if (name in setOf("syncplay-protocol", "shared", "desktop")) {
        tasks.withType<Test>().configureEach { usesService(syncplayTestServer) }
    }
}
