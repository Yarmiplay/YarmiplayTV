package com.yarmiplaytv.sync

import com.yarmiplaytv.DevicePlatform
import com.yarmiplaytv.syncplay.LanServer
import com.yarmiplaytv.syncplay.SyncplayDiscovery

/**
 * YarmiplayServerTVs answering on [ports] on this network. Broadcasts only on Wi-Fi or Ethernet ([onLocalNetwork]);
 * over mobile data it sends nothing and finds nothing.
 */
suspend fun discoverSyncplayServers(ports: Set<Int>, onLocalNetwork: Boolean = DevicePlatform.onLocalNetwork): List<LanServer> {
    if (!onLocalNetwork || ports.isEmpty()) return emptyList()
    return DevicePlatform.withMulticastLock("syncplay-discovery") { SyncplayDiscovery.discover(ports) }
}
