package com.yarmiplaytv.voice

/**
 * Runtime mic permission (Android RECORD_AUDIO). Desktop and web treat capture as always allowed
 * at this layer (the browser prompts inside getUserMedia).
 */
interface MicPermissions {
    fun granted(): Boolean

    /**
     * Ensures permission, showing the system prompt if needed (never at app launch — only when
     * called from a Mic tap). [onResult] receives whether capture may proceed.
     */
    fun request(onResult: (granted: Boolean) -> Unit)

    /** True when the system will not show the prompt again (user must open app settings). */
    fun permanentlyDenied(): Boolean

    fun openAppSettings()
}

/** Desktop / tests / platforms without a runtime mic permission. */
object AlwaysGrantedMicPermissions : MicPermissions {
    override fun granted(): Boolean = true
    override fun request(onResult: (granted: Boolean) -> Unit) = onResult(true)
    override fun permanentlyDenied(): Boolean = false
    override fun openAppSettings() = Unit
}

/**
 * Mutable holder so Android can attach [ActivityResultContracts.RequestPermission] after
 * [AppContainer] constructs [VoiceController].
 */
class MicPermissionBridge(
    @Volatile var delegate: MicPermissions = AlwaysGrantedMicPermissions,
) : MicPermissions {
    override fun granted(): Boolean = delegate.granted()
    override fun request(onResult: (granted: Boolean) -> Unit) = delegate.request(onResult)
    override fun permanentlyDenied(): Boolean = delegate.permanentlyDenied()
    override fun openAppSettings() = delegate.openAppSettings()
}
