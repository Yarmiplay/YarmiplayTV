package com.yarmiplaytv.update

import android.content.Context
import android.content.Intent
import android.net.Uri

object PlayStore {
    const val PLAY_STORE_PACKAGE = "com.android.vending"

    fun canLaunchStore(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}")).apply {
            setPackage(PLAY_STORE_PACKAGE)
        }
        return context.packageManager.resolveActivity(intent, 0) != null
    }

    fun launchStore(context: Context) {
        val packageName = context.packageName
        val storeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
            setPackage(PLAY_STORE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(storeIntent)
        } catch (_: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    }
}
