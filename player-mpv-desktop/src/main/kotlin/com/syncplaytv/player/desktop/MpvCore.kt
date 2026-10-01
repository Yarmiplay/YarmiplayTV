package com.syncplaytv.player.desktop

import com.sun.jna.Pointer
import com.sun.jna.ptr.DoubleByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference

sealed interface MpvEvent {
    data class PropertyChange(val name: String, val value: Any?) : MpvEvent
    data object StartFile : MpvEvent
    data object FileLoaded : MpvEvent
    /** [reason] is one of LibMpv.END_FILE_REASON_*; [error] is an mpv error code (negative) or 0. */
    data class EndFile(val reason: Int, val error: Int) : MpvEvent
    data class Log(val prefix: String, val level: String, val text: String) : MpvEvent
    data object Seek : MpvEvent
    data object PlaybackRestart : MpvEvent
    data object VideoReconfig : MpvEvent
    data object Shutdown : MpvEvent
}

/**
 * One libmpv instance: options, commands, properties and an event thread. Video output is the render API
 * ([VideoRenderer]); mpv never opens a window of its own.
 */
class MpvCore(options: Map<String, String> = emptyMap(), logLevel: String = "warn") : AutoCloseable {
    val lib: LibMpv = LibMpv.load()
    val handle: Pointer = lib.mpv_create() ?: error("mpv_create failed")
    @Volatile private var closed = false
    private var eventThread: Thread? = null

    init {
        val defaults = mapOf(
            "vo" to "libmpv",
            "terminal" to "no",
            "config" to "no",
            "input-default-bindings" to "no",
            "input-vo-keyboard" to "no",
            "osc" to "no",
            "idle" to "yes",
            "keep-open" to "yes",
        )
        (defaults + options).forEach { (name, value) ->
            val error = lib.mpv_set_option_string(handle, name, value)
            if (error < 0) System.err.println("mpv: option $name=$value rejected: ${lib.mpv_error_string(error)}")
        }
        val error = lib.mpv_initialize(handle)
        check(error >= 0) { "mpv_initialize failed: ${lib.mpv_error_string(error)}" }
        lib.mpv_request_log_messages(handle, logLevel)
    }

    fun command(vararg args: String): Int = lib.mpv_command(handle, arrayOf(*args))
    fun commandAsync(vararg args: String): Int = lib.mpv_command_async(handle, 0, arrayOf(*args))

    fun setString(name: String, value: String): Int = lib.mpv_set_property_string(handle, name, value)
    fun setFlag(name: String, value: Boolean): Int = lib.mpv_set_property(handle, name, LibMpv.FORMAT_FLAG, IntByReference(if (value) 1 else 0))
    fun setDouble(name: String, value: Double): Int = lib.mpv_set_property(handle, name, LibMpv.FORMAT_DOUBLE, DoubleByReference(value))

    fun getString(name: String): String? {
        val pointer = lib.mpv_get_property_string(handle, name) ?: return null
        return try { pointer.getString(0, "UTF-8") } finally { lib.mpv_free(pointer) }
    }

    fun getDouble(name: String): Double? {
        val ref = DoubleByReference()
        return if (lib.mpv_get_property(handle, name, LibMpv.FORMAT_DOUBLE, ref) >= 0) ref.value else null
    }

    fun getLong(name: String): Long? {
        val ref = LongByReference()
        return if (lib.mpv_get_property(handle, name, LibMpv.FORMAT_INT64, ref) >= 0) ref.value else null
    }

    fun getFlag(name: String): Boolean? {
        val ref = IntByReference()
        return if (lib.mpv_get_property(handle, name, LibMpv.FORMAT_FLAG, ref) >= 0) ref.value != 0 else null
    }

    /** Property changes arrive as [MpvEvent.PropertyChange] with a String, Boolean, Long or Double (null if unavailable). */
    fun observe(name: String, format: Int) {
        lib.mpv_observe_property(handle, 0, name, format)
    }

    /** Starts delivering events to [listener] on a dedicated thread. */
    fun startEvents(listener: (MpvEvent) -> Unit) {
        check(eventThread == null)
        eventThread = Thread({ eventLoop(listener) }, "mpv-events").apply {
            isDaemon = true
            start()
        }
    }

    private fun eventLoop(listener: (MpvEvent) -> Unit) {
        while (!closed) {
            val event = lib.mpv_wait_event(handle, -1.0)
            val id = event.getInt(MpvEventLayout.EVENT_ID)
            if (closed) break
            val parsed = parse(id, event) ?: continue
            try {
                listener(parsed)
            } catch (t: Throwable) {
                System.err.println("mpv event listener failed: $t")
            }
            if (parsed == MpvEvent.Shutdown) break
        }
    }

    private fun parse(id: Int, event: Pointer): MpvEvent? {
        val data = event.getPointer(MpvEventLayout.DATA)
        return when (id) {
            LibMpv.EVENT_PROPERTY_CHANGE -> {
                val name = data.getPointer(MpvEventLayout.PROP_NAME).getString(0, "UTF-8")
                val value = data.getPointer(MpvEventLayout.PROP_DATA)
                val parsed: Any? = when (data.getInt(MpvEventLayout.PROP_FORMAT)) {
                    LibMpv.FORMAT_STRING -> value?.getPointer(0)?.getString(0, "UTF-8")
                    LibMpv.FORMAT_FLAG -> value?.getInt(0)?.let { it != 0 }
                    LibMpv.FORMAT_INT64 -> value?.getLong(0)
                    LibMpv.FORMAT_DOUBLE -> value?.getDouble(0)
                    else -> null
                }
                MpvEvent.PropertyChange(name, parsed)
            }
            LibMpv.EVENT_START_FILE -> MpvEvent.StartFile
            LibMpv.EVENT_FILE_LOADED -> MpvEvent.FileLoaded
            LibMpv.EVENT_END_FILE -> MpvEvent.EndFile(data.getInt(MpvEventLayout.END_REASON), data.getInt(MpvEventLayout.END_ERROR))
            LibMpv.EVENT_LOG_MESSAGE -> MpvEvent.Log(
                data.getPointer(MpvEventLayout.LOG_PREFIX).getString(0, "UTF-8"),
                data.getPointer(MpvEventLayout.LOG_LEVEL).getString(0, "UTF-8"),
                data.getPointer(MpvEventLayout.LOG_TEXT).getString(0, "UTF-8").trimEnd(),
            )
            LibMpv.EVENT_SEEK -> MpvEvent.Seek
            LibMpv.EVENT_PLAYBACK_RESTART -> MpvEvent.PlaybackRestart
            LibMpv.EVENT_VIDEO_RECONFIG -> MpvEvent.VideoReconfig
            LibMpv.EVENT_SHUTDOWN -> MpvEvent.Shutdown
            else -> null
        }
    }

    /** Close the [VideoRenderer] first: libmpv requires the render context to be freed before this. */
    override fun close() {
        if (closed) return
        closed = true
        lib.mpv_wakeup(handle)
        eventThread?.join(2_000)
        lib.mpv_terminate_destroy(handle)
    }
}
