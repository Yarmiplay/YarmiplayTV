package com.yarmiplaytv.desktop

fun main(args: Array<String>) {
    val list = args.toList()
    when {
        "--benchmark" in list -> runBenchmark(BenchmarkOptions(list))
        "--version" in list -> println("SyncplayTV $APP_VERSION, libmpv ${libmpvVersion()}")
        else -> runApp(list)
    }
}

/** Set by the Gradle build (run task and installers); "dev" when started some other way. */
val APP_VERSION: String = System.getProperty("yarmiplaytv.version") ?: "dev"
