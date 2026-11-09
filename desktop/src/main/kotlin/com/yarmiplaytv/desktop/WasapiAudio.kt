package com.yarmiplaytv.desktop

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.yarmiplaytv.voice.StreamingResampler
import com.yarmiplaytv.voice.VoicePcm
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Windows shared-mode WASAPI capture and playback.
 *
 * The stream is opened in the device mix format (no `AUTOCONVERTPCM`), then converted once
 * with [VoicePcm] / [StreamingResampler]. A failure at any step returns null so the caller
 * keeps the Java Sound (DirectSound) line. macOS stays on CoreAudio through Java Sound, and
 * Linux stays on PipeWire / PulseAudio / ALSA.
 *
 * COM objects are created on the thread that calls [openRender] / [openCapture]. Call those
 * from the playback and capture threads.
 */
internal object WasapiAudio {
    fun openRender(): WasapiRender? {
        if (!isWindows) return null
        return runCatching { openEndpoint(render = true) as? WasapiRender }.getOrNull()
    }

    fun openCapture(): WasapiCapture? {
        if (!isWindows) return null
        return runCatching { openEndpoint(render = false) as? WasapiCapture }.getOrNull()
    }

    private fun openEndpoint(render: Boolean): Any? {
        if (!ensureCom()) return null
        val ole = ole32() ?: return null
        val enumerator = createEnumerator(ole) ?: return null
        var device: Pointer? = null
        var client: Pointer? = null
        var format: Pointer? = null
        try {
            device = defaultDevice(enumerator, if (render) 0 else 1) ?: return null
            client = activateAudioClient(device) ?: return null
            format = mixFormat(client) ?: return null
            val info = parseFormat(format) ?: return null
            if (!initializeShared(client, format, info.sampleRate)) return null
            val bufferFrames = IntByReference()
            if (hr(client, SLOT_GET_BUFFER_SIZE, bufferFrames) < 0 || bufferFrames.value <= 0) return null
            val serviceIid = guid(if (render) IID_RENDER else IID_CAPTURE)
            val serviceOut = PointerByReference()
            if (hr(client, SLOT_GET_SERVICE, serviceIid, serviceOut) < 0) return null
            val service = serviceOut.value ?: return null
            if (hr(client, SLOT_START) < 0) {
                release(service)
                return null
            }
            val keptClient = client
            val keptService = service
            client = null
            return if (render) {
                WasapiRender(keptClient, keptService, bufferFrames.value, info)
            } else {
                WasapiCapture(keptClient, keptService, info)
            }
        } finally {
            if (format != null) runCatching { ole.CoTaskMemFree(format) }
            release(client)
            release(device)
            release(enumerator)
        }
    }

    private fun initializeShared(client: Pointer, format: Pointer, sampleRate: Int): Boolean {
        val def = Memory(8)
        val min = Memory(8)
        val periodHr = hr(client, SLOT_GET_DEVICE_PERIOD, def, min)
        val defaultPeriod = if (periodHr >= 0) def.getLong(0).coerceAtLeast(0L) else 200_000L
        var duration = maxOf(200_000L, defaultPeriod * 2)
        var result = hr(client, SLOT_INITIALIZE, 0, 0, duration, 0L, format, Pointer.NULL)
        if (result == AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED) {
            val frames = IntByReference()
            if (hr(client, SLOT_GET_BUFFER_SIZE, frames) < 0 || frames.value <= 0) return false
            duration = ((10_000_000.0 / sampleRate) * frames.value + 0.5).toLong()
            result = hr(client, SLOT_INITIALIZE, 0, 0, duration, 0L, format, Pointer.NULL)
        }
        return result >= 0
    }
}

internal class WasapiRender(
    private val client: Pointer,
    private val render: Pointer,
    private val bufferFrames: Int,
    private val format: WasapiMixFormat,
) {
    val sampleRate: Int get() = format.sampleRate
    private val released = AtomicBoolean(false)
    private val resampler: StreamingResampler? =
        if (format.sampleRate == VoicePcm.SAMPLE_RATE) null else StreamingResampler(VoicePcm.SAMPLE_RATE, format.sampleRate)
    private var pending = ShortArray(0)

    @Synchronized
    fun writeMono48k(pcm: ShortArray) {
        if (released.get() || pcm.isEmpty()) return
        val atDevice = resampler?.push(pcm) ?: pcm
        if (atDevice.isEmpty() && pending.isEmpty()) return
        val mono = if (pending.isEmpty()) atDevice else {
            val joined = ShortArray(pending.size + atDevice.size)
            System.arraycopy(pending, 0, joined, 0, pending.size)
            System.arraycopy(atDevice, 0, joined, pending.size, atDevice.size)
            joined
        }
        val padding = IntByReference()
        if (hr(client, SLOT_GET_CURRENT_PADDING, padding) < 0) return
        val available = (bufferFrames - padding.value).coerceAtLeast(0)
        if (available <= 0) {
            pending = capPending(mono)
            return
        }
        val frames = minOf(available, mono.size)
        val data = PointerByReference()
        if (hr(render, 3, frames, data) < 0) return
        val dest = data.value ?: return
        writeFrames(dest, mono, frames)
        if (hr(render, 4, frames, 0) < 0) return
        pending = if (frames < mono.size) capPending(mono.copyOfRange(frames, mono.size)) else ShortArray(0)
    }

    private fun capPending(samples: ShortArray): ShortArray {
        val cap = format.sampleRate / 5
        if (samples.size <= cap) return samples
        return samples.copyOfRange(samples.size - cap / 2, samples.size)
    }

    private fun writeFrames(dest: Pointer, mono: ShortArray, frames: Int) {
        val ch = format.channels
        if (format.floatSamples) {
            var offset = 0L
            for (i in 0 until frames) {
                val v = mono[i] / 32768f
                for (c in 0 until ch) {
                    dest.setFloat(offset, v)
                    offset += 4
                }
            }
        } else if (format.bitsPerSample == 16) {
            val bytes = VoicePcm.monoToInterleavedPcm16(mono.copyOf(frames), ch)
            dest.write(0, bytes, 0, bytes.size)
        } else {
            var offset = 0L
            for (i in 0 until frames) {
                val v = mono[i].toInt() shl 16
                for (c in 0 until ch) {
                    dest.setInt(offset, v)
                    offset += 4
                }
            }
        }
    }

    @Synchronized
    fun close() {
        if (!released.compareAndSet(false, true)) return
        runCatching { hr(client, SLOT_STOP) }
        release(render)
        release(client)
    }
}

internal class WasapiCapture(
    private val client: Pointer,
    private val capture: Pointer,
    private val format: WasapiMixFormat,
) {
    private val released = AtomicBoolean(false)
    private val resampler: StreamingResampler? =
        if (format.sampleRate == VoicePcm.SAMPLE_RATE) null else StreamingResampler(format.sampleRate, VoicePcm.SAMPLE_RATE)
    private var pending = ShortArray(0)

    /** Pull whatever the device has queued and return 48 kHz mono PCM16. Empty when nothing is ready. */
    @Synchronized
    fun readMono48k(): ShortArray {
        if (released.get()) return ShortArray(0)
        val mono = ArrayList<Short>(960)
        repeat(8) {
            val next = IntByReference()
            if (hr(capture, 5, next) < 0 || next.value <= 0) return@repeat
            val data = PointerByReference()
            val frames = IntByReference()
            val flags = IntByReference()
            if (hr(capture, 3, data, frames, flags, Pointer.NULL, Pointer.NULL) < 0) return@repeat
            val n = frames.value
            val silent = flags.value and AUDCLNT_BUFFERFLAGS_SILENT != 0
            if (n > 0 && !silent) {
                val chunk = readFrames(data.value, n)
                for (s in chunk) mono += s
            } else if (n > 0) {
                repeat(n) { mono += 0 }
            }
            hr(capture, 4, n)
        }
        if (mono.isEmpty() && pending.isEmpty()) return ShortArray(0)
        val raw = ShortArray(mono.size) { mono[it] }
        val at48 = resampler?.push(raw) ?: raw
        if (pending.isEmpty()) return at48
        val joined = ShortArray(pending.size + at48.size)
        System.arraycopy(pending, 0, joined, 0, pending.size)
        if (at48.isNotEmpty()) System.arraycopy(at48, 0, joined, pending.size, at48.size)
        pending = ShortArray(0)
        return joined
    }

    private fun readFrames(src: Pointer?, frames: Int): ShortArray {
        if (src == null || frames <= 0) return ShortArray(0)
        val ch = format.channels
        if (format.floatSamples) {
            val floats = FloatArray(frames * ch)
            var offset = 0L
            for (i in floats.indices) {
                floats[i] = src.getFloat(offset)
                offset += 4
            }
            return VoicePcm.interleavedFloatToMono(floats, ch)
        }
        if (format.bitsPerSample == 16) {
            val bytes = ByteArray(frames * ch * 2)
            src.read(0, bytes, 0, bytes.size)
            val interleaved = ShortArray(frames * ch) { i ->
                val lo = bytes[i * 2].toInt() and 0xff
                val hi = bytes[i * 2 + 1].toInt()
                ((hi shl 8) or lo).toShort()
            }
            return VoicePcm.interleavedPcm16ToMono(interleaved, ch)
        }
        val interleaved = ShortArray(frames * ch)
        var offset = 0L
        for (i in interleaved.indices) {
            interleaved[i] = (src.getInt(offset) shr 16).toShort()
            offset += 4
        }
        return VoicePcm.interleavedPcm16ToMono(interleaved, ch)
    }

    @Synchronized
    fun close() {
        if (!released.compareAndSet(false, true)) return
        runCatching { hr(client, SLOT_STOP) }
        release(capture)
        release(client)
    }
}

internal data class WasapiMixFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val floatSamples: Boolean,
)

private fun parseFormat(format: Pointer): WasapiMixFormat? {
    val tag = format.getShort(0).toInt() and 0xFFFF
    val channels = format.getShort(2).toInt() and 0xFFFF
    val rate = format.getInt(4)
    val bits = format.getShort(14).toInt() and 0xFFFF
    val cbSize = format.getShort(16).toInt() and 0xFFFF
    if (channels !in 1..8 || rate !in 8_000..192_000) return null
    var floatSamples = tag == WAVE_FORMAT_IEEE_FLOAT
    var pcm = tag == WAVE_FORMAT_PCM
    if (tag == WAVE_FORMAT_EXTENSIBLE && cbSize >= 22) {
        val sub = format.getInt(24)
        floatSamples = sub == WAVE_FORMAT_IEEE_FLOAT
        pcm = sub == WAVE_FORMAT_PCM
    }
    if (floatSamples && bits == 32) return WasapiMixFormat(rate, channels, bits, floatSamples = true)
    if (pcm && (bits == 16 || bits == 32)) return WasapiMixFormat(rate, channels, bits, floatSamples = false)
    return null
}

private const val SLOT_INITIALIZE = 3
private const val SLOT_GET_BUFFER_SIZE = 4
private const val SLOT_GET_CURRENT_PADDING = 6
private const val SLOT_GET_DEVICE_PERIOD = 9
private const val SLOT_START = 10
private const val SLOT_STOP = 11
private const val SLOT_GET_SERVICE = 14

private const val WAVE_FORMAT_PCM = 1
private const val WAVE_FORMAT_IEEE_FLOAT = 3
private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
private const val AUDCLNT_BUFFERFLAGS_SILENT = 0x2
private val AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED = 0x88890019.toInt()
private val RPC_E_CHANGED_MODE = 0x80010106.toInt()

private const val IID_ENUMERATOR = "A95664D2-9614-4F35-A746-DE8DB63617E6"
private const val CLSID_ENUMERATOR = "BCDE0395-E52F-467C-8E3D-C4579291692E"
private const val IID_AUDIO_CLIENT = "1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"
private const val IID_RENDER = "F294ACFC-3146-4483-A7BF-ADDCA7C260E2"
private const val IID_CAPTURE = "C8ADBD64-E71E-48a0-A4DE-185C395CD317"

private val comReady = ThreadLocal<Boolean>()

@Suppress("FunctionName")
private interface Ole32 : StdCallLibrary {
    fun CoInitializeEx(reserved: Pointer?, flags: Int): Int
    fun CoCreateInstance(clsid: Pointer, outer: Pointer?, ctx: Int, iid: Pointer, out: PointerByReference): Int
    fun CoTaskMemFree(ptr: Pointer?)
}

private fun ole32(): Ole32? = runCatching { Native.load("ole32", Ole32::class.java) as Ole32 }.getOrNull()

private fun ensureCom(): Boolean {
    if (comReady.get() == true) return true
    val ole = ole32() ?: return false
    val hr = ole.CoInitializeEx(null, 0)
    if (hr >= 0 || hr == RPC_E_CHANGED_MODE) {
        comReady.set(true)
        return true
    }
    return false
}

private fun createEnumerator(ole: Ole32): Pointer? {
    val out = PointerByReference()
    val hr = ole.CoCreateInstance(guid(CLSID_ENUMERATOR), null, 1, guid(IID_ENUMERATOR), out)
    if (hr < 0) return null
    return out.value
}

private fun defaultDevice(enumerator: Pointer, dataFlow: Int): Pointer? {
    val out = PointerByReference()
    if (hr(enumerator, 4, dataFlow, 0, out) < 0) return null
    return out.value
}

private fun activateAudioClient(device: Pointer): Pointer? {
    val out = PointerByReference()
    if (hr(device, 3, guid(IID_AUDIO_CLIENT), 1, Pointer.NULL, out) < 0) return null
    return out.value
}

private fun mixFormat(client: Pointer): Pointer? {
    val out = PointerByReference()
    if (hr(client, 8, out) < 0) return null
    return out.value
}

private fun hr(obj: Pointer, slot: Int, vararg args: Any?): Int {
    val vt = obj.getPointer(0) ?: return Int.MIN_VALUE
    val fnPtr = vt.getPointer(slot.toLong() * Native.POINTER_SIZE) ?: return Int.MIN_VALUE
    val fn = Function.getFunction(fnPtr, Function.ALT_CONVENTION)
    return fn.invokeInt(arrayOf(obj, *args))
}

private fun release(obj: Pointer?) {
    if (obj == null) return
    runCatching { hr(obj, 2) }
}

private fun guid(spec: String): Memory {
    val parts = spec.removePrefix("{").removeSuffix("}").split("-")
    val mem = Memory(16)
    mem.setInt(0, parts[0].toLong(16).toInt())
    mem.setShort(4, parts[1].toInt(16).toShort())
    mem.setShort(6, parts[2].toInt(16).toShort())
    val tail = parts[3] + parts[4]
    for (i in 0 until 8) {
        mem.setByte(8L + i, tail.substring(i * 2, i * 2 + 2).toInt(16).toByte())
    }
    return mem
}
