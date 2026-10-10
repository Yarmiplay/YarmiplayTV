package com.yarmiplaytv.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File

/** The user's Pictures folder: Windows' known folder (OneDrive may have moved it), XDG's on Linux. */
internal object PicturesFolder {
    fun resolve(
        osName: String = System.getProperty("os.name").orEmpty(),
        env: (String) -> String? = System::getenv,
        home: String = System.getProperty("user.home").orEmpty(),
    ): File {
        val os = osName.lowercase()
        val fallback = File(home, "Pictures")
        return when {
            os.startsWith("windows") -> windowsPictures() ?: fallback
            os.startsWith("mac") || os.startsWith("darwin") -> fallback
            else -> {
                val config = env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".config").path
                xdgPictures(File(config, "user-dirs.dirs"), home) ?: fallback
            }
        }
    }

    /** XDG_PICTURES_DIR from [dirsFile], e.g. `XDG_PICTURES_DIR="$HOME/Afbeeldingen"`; home itself means none. */
    internal fun xdgPictures(dirsFile: File, home: String): File? {
        val line = runCatching { dirsFile.readLines() }.getOrNull()
            ?.firstOrNull { it.trimStart().startsWith("XDG_PICTURES_DIR=") } ?: return null
        val value = line.substringAfter('=').trim().removeSurrounding("\"").replace("\$HOME", home).trimEnd('/')
        return File(value).takeIf { value.startsWith("/") && it != File(home) }
    }

    @Suppress("FunctionName")
    private interface Shell32 : Library {
        fun SHGetKnownFolderPath(folderId: Pointer, flags: Int, token: Pointer?, path: PointerByReference): Int
    }

    @Suppress("FunctionName")
    private interface Ole32 : Library {
        fun CoTaskMemFree(pointer: Pointer?)
    }

    private fun windowsPictures(): File? = runCatching {
        // FOLDERID_Pictures, {33E28130-4E1E-4676-835A-98395C3BC3BB}, laid out as a GUID struct.
        val id = Memory(16).apply {
            setInt(0, 0x33E28130)
            setShort(4, 0x4E1E)
            setShort(6, 0x4676)
            write(8, intArrayOf(0x83, 0x5A, 0x98, 0x39, 0x5C, 0x3B, 0xC3, 0xBB).map(Int::toByte).toByteArray(), 0, 8)
        }
        val path = PointerByReference()
        try {
            val result = Native.load("shell32", Shell32::class.java).SHGetKnownFolderPath(id, 0, null, path)
            if (result == 0) File(path.value.getWideString(0)) else null
        } finally {
            Native.load("ole32", Ole32::class.java).CoTaskMemFree(path.value)
        }
    }.getOrNull()
}
