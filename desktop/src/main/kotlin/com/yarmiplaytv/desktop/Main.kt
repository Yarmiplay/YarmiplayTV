package com.yarmiplaytv.desktop

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

fun main(args: Array<String>) {
    // Dark macOS title bars, like the app (Windows: useTitleBarTheme); the app sets the saved theme's before its first
    // window. Read when AWT starts, so it goes before any window.
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
    val list = args.toList()
    when {
        "--setup" in list -> enterWindowsSetup(list)
        "--benchmark" in list -> runBenchmark(BenchmarkOptions(list))
        "--version" in list -> println("YarmiplayTV $APP_VERSION, libmpv ${libmpvVersionBounded()}")
        else -> runApp(list)
    }
}

/** libmpv load can block forever on headless CI; keep `--version` usable for packaging checks. */
private fun libmpvVersionBounded(): String {
    val result = CompletableFuture<String>()
    // Daemon so a hung native load does not keep the JVM alive after we time out and print.
    Thread({ result.complete(runCatching { libmpvVersion() }.getOrElse { "unavailable: ${it.message}" }) }, "libmpv-version")
        .apply { isDaemon = true; start() }
    return try {
        result.get(10, TimeUnit.SECONDS)
    } catch (_: TimeoutException) {
        "unavailable: timed out"
    } catch (t: Throwable) {
        "unavailable: ${t.message}"
    }
}

/** Set by the Gradle build (run task and installers); "dev" when started some other way. */
val APP_VERSION: String = System.getProperty("yarmiplaytv.version") ?: "dev"

/** MyAnimeList's Client ID for this build (empty when the build had none). */
val MAL_CLIENT_ID: String = System.getProperty("yarmiplaytv.malClientId").orEmpty()
