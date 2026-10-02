package com.yarmiplaytv.player.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.DoubleByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File

/** The parts of libmpv's client.h / render.h the desktop player uses. Strings are UTF-8. */
@Suppress("FunctionName", "LocalVariableName")
interface LibMpv : Library {
    fun mpv_client_api_version(): Long
    fun mpv_error_string(error: Int): String

    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_free(data: Pointer)

    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property(ctx: Pointer, name: String, format: Int, data: DoubleByReference): Int
    fun mpv_set_property(ctx: Pointer, name: String, format: Int, data: IntByReference): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: DoubleByReference): Int
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: LongByReference): Int
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: IntByReference): Int

    /** [args] is a NULL-terminated array; JNA appends the terminator. */
    fun mpv_command(ctx: Pointer, args: Array<String>): Int
    fun mpv_command_async(ctx: Pointer, reply: Long, args: Array<String>): Int
    fun mpv_observe_property(ctx: Pointer, reply: Long, name: String, format: Int): Int
    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)

    fun mpv_render_context_create(res: PointerByReference, mpv: Pointer, params: Pointer): Int
    fun mpv_render_context_set_update_callback(ctx: Pointer, callback: UpdateCallback?, callbackCtx: Pointer?)
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_report_swap(ctx: Pointer)
    fun mpv_render_context_free(ctx: Pointer)

    fun interface UpdateCallback : Callback {
        fun invoke(ctx: Pointer?)
    }

    fun interface GetProcAddress : Callback {
        fun invoke(ctx: Pointer?, name: String?): Pointer?
    }

    @Structure.FieldOrder("get_proc_address", "get_proc_address_ctx")
    class OpenGlInitParams : Structure() {
        @JvmField var get_proc_address: GetProcAddress? = null
        @JvmField var get_proc_address_ctx: Pointer? = null
    }

    companion object {
        const val FORMAT_NONE = 0
        const val FORMAT_STRING = 1
        const val FORMAT_FLAG = 3
        const val FORMAT_INT64 = 4
        const val FORMAT_DOUBLE = 5

        const val EVENT_NONE = 0
        const val EVENT_SHUTDOWN = 1
        const val EVENT_LOG_MESSAGE = 2
        const val EVENT_COMMAND_REPLY = 5
        const val EVENT_START_FILE = 6
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
        const val EVENT_VIDEO_RECONFIG = 17
        const val EVENT_SEEK = 20
        const val EVENT_PLAYBACK_RESTART = 21
        const val EVENT_PROPERTY_CHANGE = 22

        const val END_FILE_REASON_EOF = 0
        const val END_FILE_REASON_STOP = 2
        const val END_FILE_REASON_ERROR = 4

        const val RENDER_PARAM_INVALID = 0
        const val RENDER_PARAM_API_TYPE = 1
        const val RENDER_PARAM_OPENGL_INIT_PARAMS = 2
        const val RENDER_PARAM_OPENGL_FBO = 3
        const val RENDER_PARAM_FLIP_Y = 4
        const val RENDER_PARAM_SW_SIZE = 17
        const val RENDER_PARAM_SW_FORMAT = 18
        const val RENDER_PARAM_SW_STRIDE = 19
        const val RENDER_PARAM_SW_POINTER = 20

        const val RENDER_UPDATE_FRAME = 1L

        private val os = System.getProperty("os.name").lowercase()

        /** Library names to try, most specific first. */
        private val candidates: List<String> = when {
            os.startsWith("windows") -> listOf("libmpv-2", "mpv-2", "mpv")
            os.contains("mac") -> listOf("mpv.2", "mpv")
            else -> listOf("libmpv.so.2", "libmpv.so.1", "mpv")
        }

        /** Where Homebrew and the packaged app keep libmpv, besides the system default paths. */
        private fun searchPaths(): List<String> = buildList {
            System.getProperty("compose.application.resources.dir")?.let { add(it) }
            System.getProperty("yarmiplaytv.libmpv.dir")?.let { add(it) }
            if (os.contains("mac")) addAll(listOf("/opt/homebrew/lib", "/usr/local/lib"))
        }

        @Volatile private var loaded: LibMpv? = null

        /** Loads libmpv, or throws [MpvUnavailableException] explaining how to install it. */
        fun load(): LibMpv {
            loaded?.let { return it }
            synchronized(this) {
                loaded?.let { return it }
                val extra = searchPaths().filter { File(it).isDirectory }
                if (extra.isNotEmpty()) {
                    val existing = System.getProperty("jna.library.path").orEmpty()
                    System.setProperty("jna.library.path", (extra + existing).filter { it.isNotEmpty() }.joinToString(File.pathSeparator))
                }
                val errors = mutableListOf<String>()
                for (name in candidates) {
                    try {
                        val lib = Native.load(name, LibMpv::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
                        loaded = lib
                        return lib
                    } catch (e: UnsatisfiedLinkError) {
                        errors += "$name: ${e.message?.lineSequence()?.firstOrNull()}"
                    }
                }
                throw MpvUnavailableException(installHint(), errors.joinToString("\n"))
            }
        }

        fun installHint(): String = when {
            os.startsWith("windows") -> "libmpv-2.dll is missing from the installation. Reinstall YarmiplayTV."
            os.contains("mac") -> "YarmiplayTV needs mpv's library. Install it with Homebrew: brew install mpv"
            else -> "YarmiplayTV needs libmpv. Install it with your package manager, e.g. sudo apt install libmpv2"
        }
    }
}

class MpvUnavailableException(val hint: String, details: String) : RuntimeException("$hint\n$details")

/** mpv_event field offsets (64-bit): int event_id; int error; uint64 reply_userdata; void *data. */
internal object MpvEventLayout {
    const val EVENT_ID = 0L
    const val ERROR = 4L
    const val REPLY = 8L
    const val DATA = 16L

    /** mpv_event_property: const char *name; mpv_format format; void *data. */
    const val PROP_NAME = 0L
    const val PROP_FORMAT = 8L
    const val PROP_DATA = 16L

    /** mpv_event_log_message: const char *prefix; const char *level; const char *text; int log_level. */
    const val LOG_PREFIX = 0L
    const val LOG_LEVEL = 8L
    const val LOG_TEXT = 16L
    const val LOG_LOG_LEVEL = 24L

    /** mpv_event_end_file: int reason; int error; ... */
    const val END_REASON = 0L
    const val END_ERROR = 4L
}
