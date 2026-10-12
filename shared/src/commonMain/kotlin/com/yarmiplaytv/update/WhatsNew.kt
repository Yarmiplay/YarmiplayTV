package com.yarmiplaytv.update

import com.yarmiplaytv.data.SettingsStore

/** The home screen's "What's new" card, shown once after an update. */
object WhatsNew {
    /** The version whose notes the card shows, or null: after an update from [lastSeen], when [current] has notes. */
    fun cardVersion(lastSeen: String, current: String, notes: (String) -> ReleaseNote? = ReleaseNotes::of): String? =
        current.takeIf { lastSeen.isNotEmpty() && AppVersions.isNewer(current, lastSeen) && notes(current) != null }

    /** A fresh install has nothing new to tell: it only remembers this version, without a card. */
    suspend fun onLaunch(store: SettingsStore, current: String) {
        if (store.current().lastSeenVersion.isEmpty()) store.saveLastSeenVersion(current)
    }
}
