package com.syncplaytv.desktop

fun main(args: Array<String>) {
    val list = args.toList()
    when {
        "--benchmark" in list -> runBenchmark(BenchmarkOptions(list))
        else -> println("libmpv ${libmpvVersion()}")
    }
}
