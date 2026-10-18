package com.yarmiplaytv.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens a store [url] in that store's app on this device: a Google Play page opens the Play Store itself (on a TV
 * too, where no browser would take it). False when it isn't a store page or no store app can show it.
 */
internal fun openStoreApp(context: Context, url: String): Boolean {
    val uri = Uri.parse(url)
    val id = if (uri.scheme == "market") {
        uri.getQueryParameter("id")
    } else if (uri.host == "play.google.com") {
        uri.getQueryParameter("id")
    } else {
        null
    } ?: return false
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id"))
        .setPackage(PLAY_STORE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}

private const val PLAY_STORE = "com.android.vending"
