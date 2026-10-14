package com.yarmiplaytv.player

/**
 * Where the player fetches a stream from when that isn't the address in its URL, set by the app: e.g. a Syncplay
 * server's name at the address the connection reached at home. The player's state keeps the URL as loaded.
 */
object StreamRoutes {
    @Volatile var route: (String) -> String = { it }
}
