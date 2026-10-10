package com.yarmiplaytv.screenshot

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/**
 * A folder picked in the settings gets screenshots through its document provider. Otherwise, on Android 10 and
 * later, they go to Pictures/YarmiplayTV through MediaStore, where galleries find them, without a storage
 * permission; older versions need one to write there, so they stay in the app's own Pictures folder.
 */
class AndroidScreenshotStore(context: Context) : ScreenshotStore {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val ownFolder: File
        get() = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: File(context.filesDir, "screenshots")

    override val defaultFolder: String
        get() = if (Build.VERSION.SDK_INT >= 29) MEDIA_FOLDER else ownFolder.path

    override fun scratchFile(fileName: String): File =
        File(File(context.cacheDir, "screenshots").apply { mkdirs() }, fileName).apply { delete() }

    override fun save(file: File, folder: String): String = when {
        folder.isNotEmpty() -> saveToTree(file, Uri.parse(folder))
        Build.VERSION.SDK_INT >= 29 -> saveToMediaStore(file)
        else -> {
            val dir = ownFolder.apply { mkdirs() }
            file.copyTo(File(dir, file.name), overwrite = true)
            dir.path
        }
    }

    private fun saveToTree(file: File, tree: Uri): String {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = runCatching {
            resolver.query(parent, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        val document = runCatching { DocumentsContract.createDocument(resolver, parent, MIME, file.name) }.getOrNull()
            ?: throw IOException("can't write to ${name ?: "the folder"}; pick it again in Settings")
        try {
            copy(file, document)
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
            throw e
        }
        return name ?: tree.lastPathSegment.orEmpty()
    }

    private fun saveToMediaStore(file: File): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, MIME)
            put(MediaStore.Images.Media.RELATIVE_PATH, MEDIA_FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore didn't take the screenshot")
        try {
            copy(file, uri)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return MEDIA_FOLDER
    }

    private fun copy(file: File, uri: Uri) {
        val out = resolver.openOutputStream(uri) ?: throw IOException("can't write $uri")
        out.use { file.inputStream().use { input -> input.copyTo(it) } }
    }

    private companion object {
        const val MIME = "image/jpeg"
        val MEDIA_FOLDER = "${Environment.DIRECTORY_PICTURES}/YarmiplayTV"
    }
}
