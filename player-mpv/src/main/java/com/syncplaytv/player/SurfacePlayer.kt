package com.syncplaytv.player

import android.view.SurfaceHolder

/** A [Player] that renders into an Android SurfaceView. */
interface SurfacePlayer : Player {
    /** Attach to the SurfaceView the video should render into. */
    val surfaceCallback: SurfaceHolder.Callback
}
