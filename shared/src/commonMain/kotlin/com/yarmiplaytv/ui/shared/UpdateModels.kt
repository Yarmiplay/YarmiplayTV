package com.yarmiplaytv.ui.shared

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yarmiplaytv.update.UpdateCheck
import com.yarmiplaytv.update.UpdateState
import com.yarmiplaytv.update.Updates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** "Check for updates" in Settings: the last check's result, while the screen is open. */
class UpdateCheckModel(private val updates: Updates, private val scope: CoroutineScope) {
    var checking by mutableStateOf(false)
        private set
    var result by mutableStateOf<UpdateCheck?>(null)
        private set

    fun check() {
        if (checking) return
        checking = true
        scope.launch {
            result = try {
                updates.checkNow()
            } finally {
                checking = false
            }
        }
    }
}

/** Where this copy's updates come from, for Settings. */
fun updateSourceLine(updates: Updates): String = when {
    updates.store != null -> "Updates come from ${updates.store!!.name}"
    updates.installer != null -> "Installs updates from the download page (GitHub)"
    updates.platform != null -> "Finds updates on the download page (GitHub)"
    else -> "This build doesn't look for updates"
}

/** The sentence under "YarmiplayTV 1.9 is available" for [state]. */
fun updateStateLine(updates: Updates, state: UpdateState): String {
    val installer = updates.installer
    return when (state) {
        is UpdateState.Available -> when {
            installer == null -> "Get it from the YarmiplayTV download page."
            installer.installsNow -> "Install it now: it downloads, then Android asks to update the app."
            else -> "Install it now, or turn on Automatic updates in Settings."
        }
        is UpdateState.Downloading -> state.progress?.let { "Downloading… ${percent(it)}" } ?: "Downloading…"
        is UpdateState.Ready -> when {
            installer?.installsNow == true -> installer.pendingStep() ?: "Downloaded. Select Install to finish."
            else -> "It installs when you close YarmiplayTV."
        }
        is UpdateState.Installing -> when {
            state.progress != null -> "Installing… ${percent(state.progress)}"
            installer?.installsNow == true -> "Installing…"
            else -> "Installing… YarmiplayTV will close while it finishes."
        }
        is UpdateState.Failed -> state.message
    }
}

/** "42%", or "…" when the amount isn't known. */
fun updatePercentLabel(progress: Float?): String = progress?.let(::percent) ?: "…"

private fun percent(progress: Float) = "${(progress * 100).toInt()}%"

/** The main button's text for a downloaded update. */
fun readyButtonLabel(updates: Updates): String = when {
    updates.installer?.installsNow != true -> "Restart now"
    updates.installer?.pendingStep() != null -> "Allow installs"
    else -> "Install"
}
