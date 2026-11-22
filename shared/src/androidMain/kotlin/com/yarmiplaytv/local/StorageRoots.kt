package com.yarmiplaytv.local

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/**
 * Mounted storage the app can read after the user grants access. Removable volumes and shared internal
 * storage are included. Parent directories of the app's own files folder are not walked.
 */
internal fun storageFolderUris(context: Context): List<String> {
    val roots = LinkedHashSet<File>()
    val manager = context.getSystemService(StorageManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        for (volume in manager.storageVolumes) {
            val dir = volume.directory ?: continue
            if (volume.state != Environment.MEDIA_MOUNTED && volume.state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
            roots += dir
        }
    }
    Environment.getExternalStorageDirectory()?.takeIf { it.isDirectory }?.let { roots += it }
    File("/storage").listFiles()?.forEach { child ->
        if (!child.isDirectory) return@forEach
        val name = child.name
        if (name == "emulated" || name == "self" || name.startsWith(".")) return@forEach
        roots += child
    }
    return roots.filter { it.canRead() }.map { Uri.fromFile(it).toString() }
}
