package com.yarmiplaytv.local

/** A folder inside a media folder, with how many videos it holds (its subfolders included). */
data class LocalSubfolder(val name: String, val path: String, val videos: Int)

/** One level of a media folder: its subfolders, then the videos directly in it. */
data class LocalListing(val folders: List<LocalSubfolder>, val files: List<LocalFile>)

/** Browses the flat index one folder at a time, like a file manager. */
object LocalBrowse {
    fun inFolder(files: List<LocalFile>, folder: LocalFolder): List<LocalFile> =
        files.filter { it.folderUri == folder.uri || (it.folderUri.isEmpty() && it.folder == folder.name) }

    /** What's in [path] ("" for the media folder itself, else like "Show/Season 1") of [folder]. */
    fun list(files: List<LocalFile>, folder: LocalFolder, path: String): LocalListing {
        val prefix = if (path.isEmpty()) "" else "$path/"
        val counts = HashMap<String, Int>()
        val direct = ArrayList<LocalFile>()
        for (file in inFolder(files, folder)) {
            when {
                file.directory == path -> direct += file
                file.directory.startsWith(prefix) -> {
                    val child = file.directory.removePrefix(prefix).substringBefore('/')
                    counts[child] = (counts[child] ?: 0) + 1
                }
            }
        }
        return LocalListing(
            folders = counts.map { (name, n) -> LocalSubfolder(name, prefix + name, n) }.sortedWith(compareBy(naturalOrder) { it.name }),
            files = direct.sortedWith(compareBy(naturalOrder) { it.name }),
        )
    }

    /** Case-insensitive, with runs of digits compared as numbers: "Episode 2" before "Episode 10". */
    val naturalOrder: Comparator<String> = Comparator { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val endA = digitsEnd(a, i)
                val endB = digitsEnd(b, j)
                val na = a.substring(i, endA).trimStart('0')
                val nb = b.substring(j, endB).trimStart('0')
                val byNumber = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
                if (byNumber != 0) return@Comparator byNumber
                i = endA
                j = endB
            } else {
                val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (byChar != 0) return@Comparator byChar
                i++
                j++
            }
        }
        (a.length - i).compareTo(b.length - j).takeIf { it != 0 } ?: a.compareTo(b)
    }

    private fun digitsEnd(s: String, from: Int): Int {
        var end = from
        while (end < s.length && s[end].isDigit()) end++
        return end
    }
}
