package com.yarmiplaytv.media

import okhttp3.OkHttpClient

/**
 * One OkHttp client with OkHttp's default settings, whose connection pool and dispatcher threads every client of the
 * app shares: each one is made with `SharedHttp.base.newBuilder()` and its own timeouts, DNS and so on, instead of
 * `OkHttpClient.Builder()`, which would start a pool and threads of its own.
 */
object SharedHttp {
    val base: OkHttpClient by lazy { OkHttpClient() }
}
