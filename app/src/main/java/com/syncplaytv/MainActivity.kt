package com.syncplaytv

import android.os.Bundle
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import com.syncplaytv.player.MpvPlayer

class MainActivity : ComponentActivity() {
    private var player: MpvPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = MpvPlayer(this)
        player = p
        val view = SurfaceView(this)
        view.holder.addCallback(p.surfaceCallback)
        setContentView(view)
        intent.getStringExtra("url")?.let { url ->
            view.post { p.load(url, startPaused = false) }
        }
    }
}
