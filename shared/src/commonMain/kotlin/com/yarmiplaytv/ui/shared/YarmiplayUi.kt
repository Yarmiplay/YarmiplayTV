package com.yarmiplaytv.ui.shared

import com.yarmiplaytv.AppContainer
import com.yarmiplaytv.data.KnownServer
import com.yarmiplaytv.sync.SyncController
import com.yarmiplaytv.syncplay.DeviceState
import com.yarmiplaytv.syncplay.RoomState
import com.yarmiplaytv.syncplay.RoomUser
import com.yarmiplaytv.syncplay.ServerKind
import com.yarmiplaytv.syncplay.YarmiplayInfo

/** What to show while a YarmiplayServerTV decides about this device; null when there's nothing to ask. */
sealed interface DeviceAccessPrompt {
    val title: String
    val message: String

    /** The host has to approve this device; they see the same [fingerprint] in their server app. */
    data class Waiting(val fingerprint: String, val deviceName: String) : DeviceAccessPrompt {
        override val title get() = "Waiting for the host to approve this device"
        override val message get() = "Ask the host to approve \"$deviceName\" and check that they see the same code."
    }

    /** The server only admits approved devices and this connection didn't ask. */
    data object NotApproved : DeviceAccessPrompt {
        override val title get() = "This device isn't approved on this server"
        override val message get() = "The host only lets in devices they approved. Ask them for access?"
    }

    data class Refused(override val title: String, override val message: String) : DeviceAccessPrompt
}

fun deviceAccessPrompt(info: YarmiplayInfo, deviceName: String): DeviceAccessPrompt? = when (val d = info.device) {
    is DeviceState.Pending -> DeviceAccessPrompt.Waiting(d.fingerprint, deviceName)
    DeviceState.Required -> DeviceAccessPrompt.NotApproved
    DeviceState.Denied -> DeviceAccessPrompt.Refused("The host turned this device down", "You can ask again, or ask the host to approve it from their server app.")
    DeviceState.Expired -> DeviceAccessPrompt.Refused("Nobody approved this device in time", "The request ran out after 10 minutes. Ask again when the host is around.")
    DeviceState.Revoked -> DeviceAccessPrompt.Refused("The host removed this device", "This device can't join this server anymore unless the host approves it again.")
    DeviceState.None, DeviceState.Approved -> null
}

/** "Ask again" (requesting access) or, while waiting, cancel; closing a refused prompt leaves the room too. */
fun requestDeviceAccess(container: AppContainer) = container.sync.reconnect(requestAccess = true)

fun closeDeviceAccess(container: AppContainer) = container.sync.disconnect()

/** "YarmiplayServerTV · approved device" or "· password"; null on stock Syncplay servers. */
fun serverStatusLine(info: YarmiplayInfo): String? {
    if (info.serverKind != ServerKind.YARMIPLAY) return null
    return when {
        info.approvedDevice -> "YarmiplayServerTV · approved device"
        info.access == "password" -> "YarmiplayServerTV · password"
        else -> "YarmiplayServerTV"
    }
}

/** Whether the server at [host]:[port] last admitted only approved devices, so it needs no password. */
fun approvedOnly(known: Map<String, KnownServer>, host: String, port: String): Boolean {
    val p = port.toIntOrNull() ?: return false
    return known[SyncController.serverKey(host, p)]?.let { it.yarmiplay && it.access == "approved" } == true
}

/** A note for a room user on a relaying server who can't share or fetch files there (a stock Syncplay client). */
fun relayNote(room: RoomState, user: RoomUser): String? =
    if (room.yarmiplay.relayActive && !user.yarmiplay && user.name != room.username) "no file sharing" else null
