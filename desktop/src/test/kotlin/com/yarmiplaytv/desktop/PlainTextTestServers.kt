package com.yarmiplaytv.desktop

import com.yarmiplaytv.data.SettingsStore
import kotlinx.coroutines.runBlocking

/**
 * The test Syncplay servers are local and unencrypted: connect without asking first, as Settings > Syncing's switch
 * does. Without it the connection waits for an answer to the plain-text question nobody gives.
 */
internal fun SettingsStore.allowingPlainText(): SettingsStore = apply { runBlocking { saveSyncplayPlainText(true) } }
