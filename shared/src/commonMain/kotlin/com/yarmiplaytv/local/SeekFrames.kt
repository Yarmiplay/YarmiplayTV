package com.yarmiplaytv.local

import androidx.compose.ui.graphics.ImageBitmap

/** Frames from inside a video on this device, for the seek bar's preview. */
interface SeekFrames {
    /** A frame of [uri] at or a little before [seconds], [width] pixels wide; null when there can't be one. Blocks. */
    fun frame(uri: String, seconds: Double, width: Int): ImageBitmap?

    /** Done with previews for now: let go of whatever is kept open between frames. */
    fun release() {}

    companion object {
        /**
         * Whether [url] is a file on this device, the only kind the seek bar previews: a stream would be downloaded
         * again from the server for every frame.
         */
        fun canPreview(url: String): Boolean = url.startsWith("file:") || url.startsWith("content:") || "://" !in url
    }
}
