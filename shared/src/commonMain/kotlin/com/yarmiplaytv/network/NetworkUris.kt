package com.yarmiplaytv.network

/** `ytvnet://<location id>/<relative path>` — the password stays in settings, never in the address. */
object NetworkUris {
    const val SCHEME = "ytvnet"

    fun isNetwork(uri: String): Boolean = uri.startsWith("$SCHEME://")

    fun of(locationId: String, relativePath: String): String {
        val path = NetworkLocations.normalizeRelative(relativePath) ?: ""
        return if (path.isEmpty()) "$SCHEME://$locationId" else "$SCHEME://$locationId/$path"
    }

    /** Location id and the relative path, or null when [uri] isn't one of ours. */
    fun parse(uri: String): Pair<String, String>? {
        if (!isNetwork(uri)) return null
        val rest = uri.removePrefix("$SCHEME://")
        val id = rest.substringBefore('/')
        if (id.isEmpty() || id.any { it == '.' || it == ':' }) return null
        val path = NetworkLocations.normalizeRelative(rest.substringAfter('/', "")) ?: return null
        return id to path
    }

    fun fileName(uri: String): String? = parse(uri)?.second?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
}
