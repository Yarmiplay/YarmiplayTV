package com.yarmiplaytv.network

import java.io.File

actual fun networkBackend(cacheDir: File): NetworkBackend = JvmNetworkBackend(cacheDir)
