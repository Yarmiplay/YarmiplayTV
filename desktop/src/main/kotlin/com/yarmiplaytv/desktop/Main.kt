package com.yarmiplaytv.desktop

fun main(args: Array<String>) {
    // Dark macOS title bars, like the app (Windows: useTitleBarTheme); the app sets the saved theme's before its first
    // window. Read when AWT starts, so it goes before any window.
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
    val list = args.toList()
    when {
        "--benchmark" in list -> runBenchmark(BenchmarkOptions(list))
        "--version" in list -> println("YarmiplayTV $APP_VERSION, libmpv ${libmpvVersion()}")
        else -> runApp(list)
    }
}

/** Set by the Gradle build (run task and installers); "dev" when started some other way. */
val APP_VERSION: String = System.getProperty("yarmiplaytv.version") ?: "dev"

/** MyAnimeList's Client ID for this build (empty when the build had none). */
val MAL_CLIENT_ID: String = System.getProperty("yarmiplaytv.malClientId").orEmpty()
