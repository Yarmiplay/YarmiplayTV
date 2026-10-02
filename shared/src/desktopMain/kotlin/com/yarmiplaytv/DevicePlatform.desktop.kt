package com.yarmiplaytv

import java.net.InetAddress

actual object DevicePlatform {
    actual val deviceModel: String by lazy {
        System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
            ?: System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
            ?: runCatching { InetAddress.getLocalHost().hostName.substringBefore('.') }.getOrDefault("")
    }

    actual val isEmulator: Boolean get() = false

    actual suspend fun <T> withMulticastLock(tag: String, block: suspend () -> T): T = block()
}
