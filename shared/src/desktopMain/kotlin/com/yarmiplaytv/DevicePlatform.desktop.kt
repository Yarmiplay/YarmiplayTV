package com.yarmiplaytv

import java.net.InetAddress

actual object DevicePlatform {
    actual val deviceModel: String by lazy {
        System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
            ?: System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
            ?: runCatching { InetAddress.getLocalHost().hostName.substringBefore('.') }.getOrDefault("")
    }

    actual val deviceName: String get() = deviceModel

    actual val isEmulator: Boolean get() = false

    /** Desktops are on Wi-Fi or Ethernet (a phone's tethered connection looks the same, and is left to the user). */
    actual val onLocalNetwork: Boolean get() = true

    /** Desktops can't tell; they are taken to be on a home connection. */
    actual val onUnmeteredNetwork: Boolean get() = true

    actual suspend fun <T> withMulticastLock(tag: String, block: suspend () -> T): T = block()
}
