package com.yarmiplaytv.local

/**
 * How an Android build reaches files outside its own storage.
 * The Play bundle uses the folder picker. The website APK may scan mounted volumes after all-files access.
 */
enum class StorageAccess {
    AllFiles,
    FolderPicker,
}

object StorageAccessPolicy {
    /** [declaresAllFilesAccess] is true only when this build's manifest asks for MANAGE_EXTERNAL_STORAGE. */
    fun forBuild(declaresAllFilesAccess: Boolean): StorageAccess =
        if (declaresAllFilesAccess) StorageAccess.AllFiles else StorageAccess.FolderPicker

    fun actionLabel(access: StorageAccess): String = when (access) {
        StorageAccess.AllFiles -> "Scan USB and internal storage"
        StorageAccess.FolderPicker -> "Choose a folder or USB drive"
    }

    /** Shown when the device has no documents UI, so FTP, SMB, links and Syncplay stay the way in. */
    const val NO_DOCUMENTS_UI =
        "This device has no folder picker. FTP, SMB, web links and Syncplay still work."
}

/** Display name of a DocumentsContract tree id (`primary:Movies`, `ABCD-1234:`). */
fun documentTreeLabel(documentId: String): String {
    val path = documentId.substringAfter(':', documentId)
    val leaf = path.trimEnd('/').substringAfterLast('/')
    if (leaf.isNotEmpty()) return leaf
    val volume = documentId.substringBefore(':')
    return if (volume == "primary" || volume == documentId) "Internal storage" else volume
}

/** One document inside a tree the user granted. [mime] is the provider's type, or null when it didn't say. */
data class SafEntry(
    val id: String,
    val name: String,
    val mime: String?,
    val size: Long = 0L,
    val modified: Long = 0L,
)

/** What to do with one document while walking a granted tree. */
sealed class SafBrowseStep {
    data class Descend(val id: String, val directory: String) : SafBrowseStep()
    data class Media(
        val id: String,
        val name: String,
        val size: Long,
        val modified: Long,
        val directory: String,
    ) : SafBrowseStep()
}

/** DocumentsContract's directory type. Kept as a string so the walk can be tested without Android. */
const val SAF_DIRECTORY_MIME = "vnd.android.document/directory"

/**
 * One document in a Storage Access Framework tree. Hidden directories are not walked.
 * Videos and audio become files; everything else is skipped. [directory] is the path under the picked folder.
 */
fun classifySafEntry(entry: SafEntry, directory: String): SafBrowseStep? {
    if (entry.name.isEmpty()) return null
    if (entry.mime == SAF_DIRECTORY_MIME) {
        if (entry.name.startsWith(".")) return null
        val next = if (directory.isEmpty()) entry.name else "$directory/${entry.name}"
        return SafBrowseStep.Descend(entry.id, next)
    }
    if (!LocalMatcher.isMedia(entry.name, entry.mime)) return null
    return SafBrowseStep.Media(entry.id, entry.name, entry.size, entry.modified, directory)
}

/**
 * Walks a granted tree the way the Android library does: breadth-first, hidden folders skipped,
 * at most [maxFiles] videos and audio files. [childrenOf] lists one folder's documents.
 */
fun browseSaf(
    rootId: String,
    maxFiles: Int = LocalLibrary.MAX_FILES,
    childrenOf: (parentId: String) -> List<SafEntry>,
): List<SafBrowseStep.Media> {
    val out = ArrayList<SafBrowseStep.Media>()
    val pending = ArrayDeque<Pair<String, String>>()
    pending.add(rootId to "")
    while (pending.isNotEmpty() && out.size < maxFiles) {
        val (parent, directory) = pending.removeFirst()
        for (entry in childrenOf(parent)) {
            when (val step = classifySafEntry(entry, directory)) {
                is SafBrowseStep.Descend -> pending.add(step.id to step.directory)
                is SafBrowseStep.Media -> if (out.size < maxFiles) out += step
                null -> Unit
            }
        }
    }
    return out
}
