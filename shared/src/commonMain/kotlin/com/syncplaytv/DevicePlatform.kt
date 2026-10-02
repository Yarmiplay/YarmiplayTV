package com.syncplaytv

/** Facts about the device the shared code needs to ask the platform for. */
expect object DevicePlatform {
    /** Device model on Android, host name on desktop; part of the default Syncplay name. */
    val deviceModel: String

    /** True on the Android emulator, where the host machine is reachable at 10.0.2.2. */
    val isEmulator: Boolean

    /** Runs [block] while LAN multicast replies can be received (Android needs a Wi-Fi multicast lock). */
    suspend fun <T> withMulticastLock(tag: String, block: suspend () -> T): T
}

/** Default Syncplay user name, e.g. "TV-sdkgoogleatv" or "Phone-Pixel8". */
fun defaultSyncplayName(kind: DeviceKind): String {
    val prefix = when (kind) {
        DeviceKind.TV -> "TV"
        DeviceKind.PHONE -> "Phone"
        DeviceKind.TABLET -> "Tablet"
        DeviceKind.DESKTOP -> "PC"
    }
    val model = DevicePlatform.deviceModel.replace(Regex("[^A-Za-z0-9]"), "").take(16 - prefix.length - 1)
    return if (model.isEmpty()) prefix else "$prefix-$model"
}
