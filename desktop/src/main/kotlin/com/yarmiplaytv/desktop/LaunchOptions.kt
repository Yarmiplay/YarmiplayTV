package com.yarmiplaytv.desktop

import com.yarmiplaytv.data.SyncplayProfile
import java.io.File

/**
 * Command line, compatible with the official Syncplay client's main options:
 * `YarmiplayTV [--host host[:port]] [--name name] [--room room] [--password pw] [file]`.
 * Room options apply to this run only; they aren't saved.
 */
internal data class LaunchOptions(
    val files: List<File> = emptyList(),
    val host: String? = null,
    val port: Int? = null,
    val name: String? = null,
    val room: String? = null,
    val password: String? = null,
) {
    val joinsRoom: Boolean get() = host != null || room != null

    /** [saved] with the command-line room options applied, or null when there are none. */
    fun profile(saved: SyncplayProfile, defaultName: String): SyncplayProfile? {
        if (!joinsRoom) return null
        return saved.copy(
            host = host ?: saved.host,
            port = port ?: saved.port,
            username = name ?: saved.username.ifBlank { defaultName },
            room = room ?: saved.room,
            password = password ?: saved.password,
        )
    }

    companion object {
        private val withValue = setOf("--host", "-a", "--name", "-n", "--room", "-r", "--password", "-p")
        private val ignored = setOf("--benchmark", "--version")

        fun parse(args: List<String>): LaunchOptions {
            var options = LaunchOptions()
            val loose = mutableListOf<String>()
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                val value = args.getOrNull(i + 1)
                when {
                    arg in withValue && value != null -> {
                        options = when (arg) {
                            "--host", "-a" -> {
                                val (host, port) = splitHost(value)
                                options.copy(host = host, port = port ?: options.port)
                            }
                            "--name", "-n" -> options.copy(name = value)
                            "--room", "-r" -> options.copy(room = value)
                            else -> options.copy(password = value)
                        }
                        i += 2
                        continue
                    }
                    arg in ignored || arg.startsWith("-") -> Unit
                    else -> loose += arg
                }
                i++
            }
            return options.copy(files = files(loose))
        }

        /** "host", "host:port" or "[v6]:port". */
        private fun splitHost(value: String): Pair<String, Int?> {
            val trimmed = value.trim()
            if (trimmed.startsWith("[")) {
                val end = trimmed.indexOf(']')
                if (end > 0) return trimmed.substring(1, end) to trimmed.substring(end + 1).removePrefix(":").toIntOrNull()
            }
            val colon = trimmed.lastIndexOf(':')
            return if (colon > 0 && trimmed.count { it == ':' } == 1) {
                trimmed.substring(0, colon) to trimmed.substring(colon + 1).toIntOrNull()
            } else {
                trimmed to null
            }
        }

        /**
         * Each loose argument is a file. Launchers that don't quote (e.g. Gradle's --args) split a path with
         * spaces into pieces; if the pieces don't exist on their own but joined do, that's the file.
         */
        private fun files(loose: List<String>): List<File> {
            if (loose.isEmpty()) return emptyList()
            val separate = loose.map(::File)
            if (separate.all { it.exists() }) return separate
            val joined = File(loose.joinToString(" "))
            return if (joined.exists()) listOf(joined) else separate
        }
    }
}
