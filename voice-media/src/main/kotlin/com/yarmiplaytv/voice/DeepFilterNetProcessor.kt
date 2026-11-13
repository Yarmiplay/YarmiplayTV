package com.yarmiplaytv.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.InputStream
import java.nio.FloatBuffer
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Low-level 10 ms hop (480 samples @ 48 kHz) backend for DeepFilterNet3.
 */
interface DeepFilterBackend : AutoCloseable {
    val name: String
    val hopSamples: Int get() = DeepFilterNetProcessor.HOP_SAMPLES
    fun setAttenuationLimitDb(db: Float)
    fun setPostFilterBeta(beta: Float)

    /**
     * Processes one 10 ms hop ([hopSamples] = 480 mono 16-bit PCM samples at 48 kHz) in-place
     * inside [pcm] starting at [offset]. Returns the estimated local SNR in dB.
     */
    fun processHop480(pcm: ShortArray, offset: Int): Float
}

/**
 * Real-time 48 kHz 20 ms frame AI noise suppressor powered by DeepFilterNet3 (MIT / Apache-2.0).
 *
 * - Runs two 10 ms hops (2 × 480 samples = 960 samples) per 20 ms Opus frame.
 * - Monitors per-frame wall-clock latency against [maxFrameBudgetMs] (default 18 ms for a 20 ms frame).
 * - If the model or native runtime fails to load, or if the device is too slow to keep up in real time,
 *   trips [isAvailable] = `false`, records [failureReason], and invokes [onFallback] so the voice engine
 *   immediately falls back to WebRTC/platform NS (or none) and notifies the user in-app.
 */
class DeepFilterNetProcessor(
    backendFactory: (() -> DeepFilterBackend?)? = null,
    attenuationLimitDb: Float = DEFAULT_ATTENUATION_LIMIT_DB,
    postFilterBeta: Float = DEFAULT_POST_FILTER_BETA,
    private val maxFrameBudgetMs: Double = DEFAULT_MAX_FRAME_BUDGET_MS,
    private val maxOverBudgetStreak: Int = DEFAULT_MAX_OVER_BUDGET_STREAK,
    private val nanoClock: () -> Long = System::nanoTime,
    private val onFallback: ((String) -> Unit)? = null,
) : AutoCloseable {
    private val lock = Any()
    private var backend: DeepFilterBackend? = null
    private var closed = false

    @Volatile
    var isAvailable: Boolean = false
        private set

    @Volatile
    var isDegraded: Boolean = false
        private set

    @Volatile
    var failureReason: String? = null
        private set

    @Volatile
    var lastFrameTimeMs: Double = 0.0
        private set

    @Volatile
    var emaFrameTimeMs: Double = 0.0
        private set

    @Volatile
    var lastLocalSnrDb: Float = 0f
        private set

    @Volatile
    var framesProcessed: Long = 0L
        private set

    private var overBudgetStreak = 0

    val backendName: String
        get() = synchronized(lock) { backend?.name ?: "unavailable" }

    init {
        synchronized(lock) {
            try {
                val created = if (backendFactory != null) {
                    backendFactory.invoke()
                } else {
                    createDefaultOnnxBackend(attenuationLimitDb, postFilterBeta)
                }
                if (created != null) {
                    created.setAttenuationLimitDb(attenuationLimitDb)
                    created.setPostFilterBeta(postFilterBeta)
                    backend = created
                    isAvailable = true
                } else {
                    isAvailable = false
                    failureReason = "DeepFilterNet3 model or runtime unavailable"
                }
            } catch (t: Throwable) {
                backend = null
                isAvailable = false
                failureReason = "DeepFilterNet3 load failed: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        if (!isAvailable) {
            failureReason?.let { onFallback?.invoke(it) }
        }
    }

    fun setAttenuationLimitDb(db: Float) {
        synchronized(lock) {
            backend?.setAttenuationLimitDb(db)
        }
    }

    fun setPostFilterBeta(beta: Float) {
        synchronized(lock) {
            backend?.setPostFilterBeta(beta)
        }
    }

    /**
     * Processes a 20 ms frame at 48 kHz (960 samples mono, or 1920 samples interleaved stereo).
     * If unavailable or degraded, returns [frame] unchanged without dropping or zeroing samples.
     */
    fun processFrame20ms(frame: ShortArray, channels: Int = 1): ShortArray {
        if (!isAvailable) return frame
        val ch = channels.coerceIn(1, 2)
        val expectedSize = FRAME_SAMPLES_20MS * ch
        if (frame.size < expectedSize) return frame

        var trippedReason: String? = null
        val result: ShortArray = synchronized(lock) {
            val active = backend
            if (closed || active == null || !isAvailable) return frame
            val t0 = nanoClock()
            try {
                val out = if (ch == 1) {
                    val copy = frame.copyOf()
                    val snr0 = active.processHop480(copy, 0)
                    val snr1 = active.processHop480(copy, HOP_SAMPLES)
                    lastLocalSnrDb = (snr0 + snr1) * 0.5f
                    copy
                } else {
                    // Downmix stereo capture to mono for DeepFilterNet3, then restore stereo interleaved.
                    val mono = ShortArray(FRAME_SAMPLES_20MS) { i ->
                        val l = frame[i * 2].toInt()
                        val r = frame[i * 2 + 1].toInt()
                        ((l + r) shr 1).toShort()
                    }
                    val snr0 = active.processHop480(mono, 0)
                    val snr1 = active.processHop480(mono, HOP_SAMPLES)
                    lastLocalSnrDb = (snr0 + snr1) * 0.5f
                    val stereoOut = ShortArray(frame.size)
                    for (i in 0 until FRAME_SAMPLES_20MS) {
                        val s = mono[i]
                        stereoOut[i * 2] = s
                        stereoOut[i * 2 + 1] = s
                    }
                    stereoOut
                }
                val elapsedMs = (nanoClock() - t0).coerceAtLeast(0L) / 1_000_000.0
                lastFrameTimeMs = elapsedMs
                emaFrameTimeMs = if (framesProcessed == 0L) elapsedMs else (emaFrameTimeMs * 0.85 + elapsedMs * 0.15)
                framesProcessed++

                if (elapsedMs > maxFrameBudgetMs) {
                    overBudgetStreak++
                } else {
                    overBudgetStreak = maxOf(0, overBudgetStreak - 1)
                }

                if (overBudgetStreak >= maxOverBudgetStreak) {
                    isDegraded = true
                    isAvailable = false
                    trippedReason = "device too slow for real-time 48 kHz (${"%.1f".format(elapsedMs)} ms/frame)"
                    failureReason = trippedReason
                    try {
                        active.close()
                    } catch (_: Throwable) {
                    }
                    backend = null
                }
                out
            } catch (t: Throwable) {
                isDegraded = true
                isAvailable = false
                trippedReason = "DeepFilterNet3 runtime error: ${t.message ?: t.javaClass.simpleName}"
                failureReason = trippedReason
                try {
                    active.close()
                } catch (_: Throwable) {
                }
                backend = null
                frame
            }
        }
        if (trippedReason != null) {
            onFallback?.invoke(trippedReason!!)
        }
        return result
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            isAvailable = false
            val active = backend
            backend = null
            try {
                active?.close()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FFT_SIZE = 960
        const val HOP_SAMPLES = 480
        const val FRAME_SAMPLES_20MS = 960
        const val FREQ_SIZE = FFT_SIZE / 2 + 1 // 481
        const val NB_ERB = 32
        const val MIN_NB_ERB_FREQS = 2
        const val NB_DF = 96
        const val DF_ORDER = 5
        /** Bundled mobile DeepFilterNet3 is the low-latency export (`conv_lookahead = 0`). */
        const val CONV_LOOKAHEAD = 0
        const val DEFAULT_ATTENUATION_LIMIT_DB = 50f
        /** Official runtime leaves the optional post-filter off unless explicitly enabled. */
        const val DEFAULT_POST_FILTER_BETA = 0f
        /**
         * Stateless ONNX calls reset the GRU. A short feature window gives the last frame recurrent
         * context without a second noise stage. 16 hops is 160 ms and still far under the 10 ms budget.
         */
        const val FEATURE_CONTEXT_FRAMES = 16
        const val DEFAULT_MAX_FRAME_BUDGET_MS = 18.0
        const val DEFAULT_MAX_OVER_BUDGET_STREAK = 5
        const val MODEL_RESOURCE_NAME = "deepfilternet3_onnx.tar.gz"

        fun createDefaultOnnxBackend(
            attenuationLimitDb: Float = DEFAULT_ATTENUATION_LIMIT_DB,
            postFilterBeta: Float = DEFAULT_POST_FILTER_BETA,
            modelStreamProvider: () -> InputStream? = {
                DeepFilterNetProcessor::class.java.classLoader?.getResourceAsStream(MODEL_RESOURCE_NAME)
            },
        ): DeepFilterBackend? {
            val stream = modelStreamProvider() ?: return null
            return stream.use { OnnxDeepFilterBackend.fromTarGz(it, attenuationLimitDb, postFilterBeta) }
        }
    }
}

/**
 * ONNX Runtime + libDF DSP implementation of DeepFilterNet3 (48 kHz, 960-pt Vorbis STFT,
 * 480-sample hop, 32 ERB bands, 96 DF bins, 5-tap deep filtering).
 *
 * Matches the mathematical formulation of `Rikorose/DeepFilterNet/libDF/src/lib.rs` and `tract.rs`.
 */
class OnnxDeepFilterBackend private constructor(
    private val env: OrtEnvironment,
    private val sessionOptions: OrtSession.SessionOptions,
    private val encSession: OrtSession,
    private val erbDecSession: OrtSession,
    private val dfDecSession: OrtSession,
    private val dfOrder: Int,
    private val convLookahead: Int,
    private val dfLookahead: Int,
    attenuationLimitDb: Float,
    postFilterBeta: Float,
) : DeepFilterBackend {
    override val name: String = "deepfilternet3-onnx"

    private val lookahead = maxOf(convLookahead, dfLookahead)
    private val xLen = maxOf(dfOrder, lookahead)
    private val yLen = dfOrder + convLookahead
    private val fft = RealFft960()
    private val erbBands: IntArray = computeErbBands(
        sr = DeepFilterNetProcessor.SAMPLE_RATE,
        fftSize = DeepFilterNetProcessor.FFT_SIZE,
        nbBands = DeepFilterNetProcessor.NB_ERB,
        minNbFreqs = DeepFilterNetProcessor.MIN_NB_ERB_FREQS,
    )
    private val window: FloatArray = FloatArray(DeepFilterNetProcessor.FFT_SIZE).also { win ->
        val windowSizeH = DeepFilterNetProcessor.FFT_SIZE / 2.0
        for (i in win.indices) {
            val s = sin(0.5 * PI * (i + 0.5) / windowSizeH)
            win[i] = sin(0.5 * PI * s * s).toFloat()
        }
    }
    private val wnorm: Float =
        1f / ((DeepFilterNetProcessor.FFT_SIZE * DeepFilterNetProcessor.FFT_SIZE).toFloat() / (2 * DeepFilterNetProcessor.HOP_SAMPLES))
    private val normAlpha: Float = exp(-DeepFilterNetProcessor.HOP_SAMPLES.toDouble() / DeepFilterNetProcessor.SAMPLE_RATE).toFloat()

    private val analysisMem = FloatArray(DeepFilterNetProcessor.FFT_SIZE - DeepFilterNetProcessor.HOP_SAMPLES)
    private val synthesisMem = FloatArray(DeepFilterNetProcessor.FFT_SIZE - DeepFilterNetProcessor.HOP_SAMPLES)
    private val timeBuf = FloatArray(DeepFilterNetProcessor.FFT_SIZE)
    private val specRe = FloatArray(DeepFilterNetProcessor.FREQ_SIZE)
    private val specIm = FloatArray(DeepFilterNetProcessor.FREQ_SIZE)

    private val meanNormState = FloatArray(DeepFilterNetProcessor.NB_ERB) { i ->
        -60f + i * ((-90f - (-60f)) / (DeepFilterNetProcessor.NB_ERB - 1).toFloat())
    }
    private val unitNormState = FloatArray(DeepFilterNetProcessor.NB_DF) { i ->
        0.001f + i * ((0.0001f - 0.001f) / (DeepFilterNetProcessor.NB_DF - 1).toFloat())
    }

    // Feature window passed to the stateless ONNX sessions (GRU state is not exposed).
    private val contextFrames = DeepFilterNetProcessor.FEATURE_CONTEXT_FRAMES
    private val featErbWindow = FloatArray(contextFrames * DeepFilterNetProcessor.NB_ERB)
    private val featSpecWindow = FloatArray(2 * contextFrames * DeepFilterNetProcessor.NB_DF)

    // Rolling spectra sized like libDF tract.rs: Y holds df_order + conv_lookahead,
    // X holds max(df_order, lookahead). Gains land on Y[df_order - 1]; DF uses the oldest df_order of X.
    private val rollingSpecXRe = Array(xLen) { FloatArray(DeepFilterNetProcessor.FREQ_SIZE) }
    private val rollingSpecXIm = Array(xLen) { FloatArray(DeepFilterNetProcessor.FREQ_SIZE) }
    private val rollingSpecYRe = Array(yLen) { FloatArray(DeepFilterNetProcessor.FREQ_SIZE) }
    private val rollingSpecYIm = Array(yLen) { FloatArray(DeepFilterNetProcessor.FREQ_SIZE) }

    private val hopInFloat = FloatArray(DeepFilterNetProcessor.HOP_SAMPLES)
    private val hopOutFloat = FloatArray(DeepFilterNetProcessor.HOP_SAMPLES)
    private val erbFeatCur = FloatArray(DeepFilterNetProcessor.NB_ERB)
    private val cplxFeatRe = FloatArray(DeepFilterNetProcessor.NB_DF)
    private val cplxFeatIm = FloatArray(DeepFilterNetProcessor.NB_DF)
    private val enhSpecRe = FloatArray(DeepFilterNetProcessor.FREQ_SIZE)
    private val enhSpecIm = FloatArray(DeepFilterNetProcessor.FREQ_SIZE)

    private var attenLimLinear: Float? = null
    private var postFilterBeta: Float = 0f
    private var skipCounter = 0

    private val minDbThresh = -10f
    private val maxDbErbThresh = 30f
    private val maxDbDfThresh = 20f

    init {
        setAttenuationLimitDb(attenuationLimitDb)
        setPostFilterBeta(postFilterBeta)
    }

    override fun setAttenuationLimitDb(db: Float) {
        val lim = abs(db)
        attenLimLinear = when {
            lim >= 100f -> null
            lim < 0.01f -> 1f
            else -> 10f.pow(-lim / 20f)
        }
    }

    override fun setPostFilterBeta(beta: Float) {
        postFilterBeta = beta.coerceAtLeast(0f)
    }

    override fun processHop480(pcm: ShortArray, offset: Int): Float {
        val hop = DeepFilterNetProcessor.HOP_SAMPLES
        var energy = 0f
        for (i in 0 until hop) {
            val v = pcm[offset + i] / 32768f
            hopInFloat[i] = v
            energy += v * v
        }
        val rmsSq = energy / hop
        if (rmsSq < 1e-7f) {
            skipCounter++
        } else {
            skipCounter = 0
        }
        if (skipCounter > 5) {
            for (i in 0 until hop) pcm[offset + i] = 0
            return -15f
        }

        // Rotate rolling spectrum buffers left by 1 frame.
        rotateSpecHistory(rollingSpecXRe, rollingSpecXIm)
        rotateSpecHistory(rollingSpecYRe, rollingSpecYIm)

        // Frame analysis (STFT).
        frameAnalysis(hopInFloat, specRe, specIm)
        System.arraycopy(specRe, 0, rollingSpecXRe[xLen - 1], 0, DeepFilterNetProcessor.FREQ_SIZE)
        System.arraycopy(specIm, 0, rollingSpecXIm[xLen - 1], 0, DeepFilterNetProcessor.FREQ_SIZE)
        System.arraycopy(specRe, 0, rollingSpecYRe[yLen - 1], 0, DeepFilterNetProcessor.FREQ_SIZE)
        System.arraycopy(specIm, 0, rollingSpecYIm[yLen - 1], 0, DeepFilterNetProcessor.FREQ_SIZE)

        if (attenLimLinear == 1f) {
            return 35f
        }

        // Extract ERB and complex features for current frame.
        computeFeatErb(specRe, specIm, erbFeatCur)
        computeFeatCplx(specRe, specIm, cplxFeatRe, cplxFeatIm)

        // Shift sliding feature window of length S = contextFrames.
        val nbErb = DeepFilterNetProcessor.NB_ERB
        val nbDf = DeepFilterNetProcessor.NB_DF
        System.arraycopy(featErbWindow, nbErb, featErbWindow, 0, (contextFrames - 1) * nbErb)
        System.arraycopy(erbFeatCur, 0, featErbWindow, (contextFrames - 1) * nbErb, nbErb)

        val chStride = contextFrames * nbDf
        System.arraycopy(featSpecWindow, nbDf, featSpecWindow, 0, (contextFrames - 1) * nbDf)
        System.arraycopy(cplxFeatRe, 0, featSpecWindow, (contextFrames - 1) * nbDf, nbDf)
        System.arraycopy(featSpecWindow, chStride + nbDf, featSpecWindow, chStride, (contextFrames - 1) * nbDf)
        System.arraycopy(cplxFeatIm, 0, featSpecWindow, chStride + (contextFrames - 1) * nbDf, nbDf)

        val sLong = contextFrames.toLong()
        val lastIdx = contextFrames - 1
        var lsnr = 0f

        OnnxTensor.createTensor(env, FloatBuffer.wrap(featErbWindow), longArrayOf(1, 1, sLong, nbErb.toLong())).use { erbTensor ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(featSpecWindow), longArrayOf(1, 2, sLong, nbDf.toLong())).use { specTensor ->
                val encInputs = mapOf("feat_erb" to erbTensor, "feat_spec" to specTensor)
                encSession.run(encInputs).use { encOut ->
                    @Suppress("UNCHECKED_CAST")
                    val lsnrArr = encOut.get("lsnr").get().value as Array<Array<FloatArray>>
                    lsnr = lsnrArr[0][lastIdx][0]

                    val applyGains: Boolean
                    val applyGainZeros: Boolean
                    val applyDf: Boolean
                    when {
                        lsnr < minDbThresh -> {
                            applyGains = false
                            applyGainZeros = true
                            applyDf = false
                        }
                        lsnr > maxDbErbThresh -> {
                            applyGains = false
                            applyGainZeros = false
                            applyDf = false
                        }
                        lsnr > maxDbDfThresh -> {
                            applyGains = true
                            applyGainZeros = false
                            applyDf = false
                        }
                        else -> {
                            applyGains = true
                            applyGainZeros = false
                            applyDf = true
                        }
                    }

                    val targetFrameIdx = dfOrder - 1
                    val yRe = rollingSpecYRe[targetFrameIdx]
                    val yIm = rollingSpecYIm[targetFrameIdx]

                    if (applyGains) {
                        val embVal = encOut.get("emb").get() as OnnxTensor
                        val e3Val = encOut.get("e3").get() as OnnxTensor
                        val e2Val = encOut.get("e2").get() as OnnxTensor
                        val e1Val = encOut.get("e1").get() as OnnxTensor
                        val e0Val = encOut.get("e0").get() as OnnxTensor
                        val erbDecInputs = mapOf(
                            "emb" to embVal,
                            "e3" to e3Val,
                            "e2" to e2Val,
                            "e1" to e1Val,
                            "e0" to e0Val,
                        )
                        erbDecSession.run(erbDecInputs).use { erbOut ->
                            @Suppress("UNCHECKED_CAST")
                            val mArr = erbOut.get("m").get().value as Array<Array<Array<FloatArray>>>
                            val gains = mArr[0][0][lastIdx]
                            applyInterpBandGain(yRe, yIm, gains)
                        }
                        skipCounter = 0
                    } else if (applyGainZeros) {
                        yRe.fill(0f)
                        yIm.fill(0f)
                        skipCounter = 0
                    } else {
                        skipCounter++
                    }

                    System.arraycopy(yRe, 0, enhSpecRe, 0, DeepFilterNetProcessor.FREQ_SIZE)
                    System.arraycopy(yIm, 0, enhSpecIm, 0, DeepFilterNetProcessor.FREQ_SIZE)

                    if (applyDf) {
                        val embVal = encOut.get("emb").get() as OnnxTensor
                        val c0Val = encOut.get("c0").get() as OnnxTensor
                        dfDecSession.run(mapOf("emb" to embVal, "c0" to c0Val)).use { dfOut ->
                            @Suppress("UNCHECKED_CAST")
                            val coefsArr = dfOut.get("coefs").get().value as Array<Array<Array<FloatArray>>>
                            val coefsFrame = coefsArr[0][lastIdx] // [96][10]
                            for (f in 0 until nbDf) {
                                var sumRe = 0f
                                var sumIm = 0f
                                val cf = coefsFrame[f]
                                for (n in 0 until dfOrder) {
                                    val sr = rollingSpecXRe[n][f]
                                    val si = rollingSpecXIm[n][f]
                                    val cr = cf[n * 2]
                                    val ci = cf[n * 2 + 1]
                                    sumRe += sr * cr - si * ci
                                    sumIm += sr * ci + si * cr
                                }
                                enhSpecRe[f] = sumRe
                                enhSpecIm[f] = sumIm
                            }
                        }
                    }

                    val noisyIdx = (xLen - lookahead - 1).coerceAtLeast(0)
                    val noisyRe = rollingSpecXRe[noisyIdx]
                    val noisyIm = rollingSpecXIm[noisyIdx]

                    if (applyGains && postFilterBeta > 0f) {
                        applyPostFilter(noisyRe, noisyIm, enhSpecRe, enhSpecIm, postFilterBeta)
                    }

                    val lim = attenLimLinear
                    if (lim != null && lim > 0f) {
                        val keep = 1f - lim
                        for (f in 0 until DeepFilterNetProcessor.FREQ_SIZE) {
                            enhSpecRe[f] = enhSpecRe[f] * keep + noisyRe[f] * lim
                            enhSpecIm[f] = enhSpecIm[f] * keep + noisyIm[f] * lim
                        }
                    }
                }
            }
        }

        // Frame synthesis (ISTFT + overlap-add).
        frameSynthesis(enhSpecRe, enhSpecIm, hopOutFloat)
        for (i in 0 until hop) {
            val clamped = hopOutFloat[i].coerceIn(-1f, 1f)
            pcm[offset + i] = (clamped * 32767f).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return lsnr
    }

    private fun rotateSpecHistory(re: Array<FloatArray>, im: Array<FloatArray>) {
        val firstRe = re[0]
        val firstIm = im[0]
        for (i in 0 until re.size - 1) {
            re[i] = re[i + 1]
            im[i] = im[i + 1]
        }
        re[re.size - 1] = firstRe
        im[im.size - 1] = firstIm
    }

    private fun frameAnalysis(input: FloatArray, outRe: FloatArray, outIm: FloatArray) {
        val hop = DeepFilterNetProcessor.HOP_SAMPLES
        val split = DeepFilterNetProcessor.FFT_SIZE - hop
        for (i in 0 until split) {
            timeBuf[i] = analysisMem[i] * window[i]
        }
        for (i in 0 until hop) {
            timeBuf[split + i] = input[i] * window[split + i]
        }
        System.arraycopy(input, 0, analysisMem, 0, hop)
        fft.forward(timeBuf, outRe, outIm)
        for (f in outRe.indices) {
            outRe[f] *= wnorm
            outIm[f] *= wnorm
        }
    }

    private fun frameSynthesis(inRe: FloatArray, inIm: FloatArray, output: FloatArray) {
        val hop = DeepFilterNetProcessor.HOP_SAMPLES
        fft.inverse(inRe, inIm, timeBuf)
        for (i in timeBuf.indices) {
            timeBuf[i] *= window[i]
        }
        for (i in 0 until hop) {
            output[i] = timeBuf[i] + synthesisMem[i]
        }
        System.arraycopy(timeBuf, hop, synthesisMem, 0, DeepFilterNetProcessor.FFT_SIZE - hop)
    }

    private fun computeFeatErb(re: FloatArray, im: FloatArray, out: FloatArray) {
        var bcsum = 0
        for (b in erbBands.indices) {
            val bandSize = erbBands[b]
            val k = 1f / bandSize
            var sum = 0f
            for (j in 0 until bandSize) {
                val idx = bcsum + j
                val r = re[idx]
                val m = im[idx]
                sum += (r * r + m * m) * k
            }
            bcsum += bandSize
            val db = log10(sum + 1e-10f) * 10f
            val s = db * (1f - normAlpha) + meanNormState[b] * normAlpha
            meanNormState[b] = s
            out[b] = (db - s) / 40f
        }
    }

    private fun computeFeatCplx(re: FloatArray, im: FloatArray, outRe: FloatArray, outIm: FloatArray) {
        for (f in 0 until DeepFilterNetProcessor.NB_DF) {
            val r = re[f]
            val m = im[f]
            val norm = sqrt(r * r + m * m)
            val s = norm * (1f - normAlpha) + unitNormState[f] * normAlpha
            unitNormState[f] = s
            val inv = 1f / sqrt(max(s, 1e-12f))
            outRe[f] = r * inv
            outIm[f] = m * inv
        }
    }

    private fun applyInterpBandGain(re: FloatArray, im: FloatArray, gains: FloatArray) {
        var bcsum = 0
        for (b in erbBands.indices) {
            val bandSize = erbBands[b]
            val g = gains[b]
            for (j in 0 until bandSize) {
                val idx = bcsum + j
                re[idx] *= g
                im[idx] *= g
            }
            bcsum += bandSize
        }
    }

    private fun applyPostFilter(
        noisyRe: FloatArray,
        noisyIm: FloatArray,
        enhRe: FloatArray,
        enhIm: FloatArray,
        beta: Float,
    ) {
        val betaP1 = beta + 1f
        val eps = 1e-12f
        val halfPi = (PI / 2.0).toFloat()
        for (f in enhRe.indices) {
            val nNorm = sqrt(noisyRe[f] * noisyRe[f] + noisyIm[f] * noisyIm[f])
            val eNorm = sqrt(enhRe[f] * enhRe[f] + enhIm[f] * enhIm[f])
            val g = min(1f, max(eps, eNorm / (nNorm + eps)))
            val gSin = g * sin(g * halfPi)
            val ratio = g / max(gSin, eps)
            val pf = (betaP1 * g / (1f + beta * ratio * ratio)) / g
            enhRe[f] *= pf
            enhIm[f] *= pf
        }
    }

    override fun close() {
        runCatching { encSession.close() }
        runCatching { erbDecSession.close() }
        runCatching { dfDecSession.close() }
        runCatching { sessionOptions.close() }
    }

    companion object {
        fun computeErbBands(sr: Int, fftSize: Int, nbBands: Int, minNbFreqs: Int): IntArray {
            val nyq = sr / 2f
            val freqWidth = sr.toFloat() / fftSize.toFloat()
            fun freq2erb(f: Float): Float = (9.265 * ln(1.0 + f / (24.7 * 9.265))).toFloat()
            fun erb2freq(e: Float): Float = (24.7 * 9.265 * (exp(e / 9.265) - 1.0)).toFloat()
            val erbLow = freq2erb(0f)
            val erbHigh = freq2erb(nyq)
            val step = (erbHigh - erbLow) / nbBands.toFloat()
            val erb = IntArray(nbBands)
            var prevFreq = 0
            var freqOver = 0
            for (i in 1..nbBands) {
                val f = erb2freq(erbLow + i * step)
                val fb = (f / freqWidth).roundToInt()
                var nbFreqs = fb - prevFreq - freqOver
                if (nbFreqs < minNbFreqs) {
                    freqOver = minNbFreqs - nbFreqs
                    nbFreqs = minNbFreqs
                } else {
                    freqOver = 0
                }
                erb[i - 1] = nbFreqs
                prevFreq = fb
            }
            erb[nbBands - 1] += 1
            val total = erb.sum()
            val target = fftSize / 2 + 1
            if (total > target) {
                erb[nbBands - 1] -= (total - target)
            } else if (total < target) {
                erb[nbBands - 1] += (target - total)
            }
            return erb
        }

        fun fromTarGz(
            stream: InputStream,
            attenuationLimitDb: Float = DeepFilterNetProcessor.DEFAULT_ATTENUATION_LIMIT_DB,
            postFilterBeta: Float = DeepFilterNetProcessor.DEFAULT_POST_FILTER_BETA,
        ): OnnxDeepFilterBackend? {
            val entries = HashMap<String, ByteArray>()
            GZIPInputStream(stream).use { gz ->
                val header = ByteArray(512)
                while (true) {
                    var read = 0
                    while (read < 512) {
                        val n = gz.read(header, read, 512 - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read < 512 || header.all { it == 0.toByte() }) break
                    val name = header.decodeToString(0, 100).substringBefore('\u0000')
                    val sizeOctal = header.decodeToString(124, 136).substringBefore('\u0000').trim()
                    val size = if (sizeOctal.isEmpty()) 0L else sizeOctal.toLong(8)
                    val type = header[156].toInt().toChar()
                    val body = ByteArray(size.toInt())
                    var bodyRead = 0
                    while (bodyRead < body.size) {
                        val n = gz.read(body, bodyRead, body.size - bodyRead)
                        if (n < 0) break
                        bodyRead += n
                    }
                    val pad = ((512 - (size % 512)) % 512).toInt()
                    if (pad > 0) {
                        val skipBuf = ByteArray(pad)
                        var skipped = 0
                        while (skipped < pad) {
                            val n = gz.read(skipBuf, skipped, pad - skipped)
                            if (n < 0) break
                            skipped += n
                        }
                    }
                    val base = name.substringAfterLast('/')
                    if ((type == '0' || type == '\u0000') && !base.startsWith("._") && size > 0) {
                        entries[base] = body
                    }
                }
            }
            val encBytes = entries["enc.onnx"] ?: return null
            val erbBytes = entries["erb_dec.onnx"] ?: return null
            val dfBytes = entries["df_dec.onnx"] ?: return null
            val config = DfnModelConfig.parse(entries["config.ini"]?.decodeToString()) ?: return null
            if (!config.matchesBundledRuntime()) return null

            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val enc = env.createSession(encBytes, opts)
            val erb = env.createSession(erbBytes, opts)
            val df = env.createSession(dfBytes, opts)
            return OnnxDeepFilterBackend(
                env = env,
                sessionOptions = opts,
                encSession = enc,
                erbDecSession = erb,
                dfDecSession = df,
                dfOrder = config.dfOrder,
                convLookahead = config.convLookahead,
                dfLookahead = config.dfLookahead,
                attenuationLimitDb = attenuationLimitDb,
                postFilterBeta = postFilterBeta,
            )
        }
    }
}

/**
 * `[df]` / `[deepfilternet]` keys from the model tar's `config.ini`.
 * The desktop FFT and feature layout are fixed to the bundled 48 kHz / 960 / 480 model.
 */
internal data class DfnModelConfig(
    val sampleRate: Int,
    val fftSize: Int,
    val hopSize: Int,
    val nbErb: Int,
    val nbDf: Int,
    val dfOrder: Int,
    val convLookahead: Int,
    val dfLookahead: Int,
) {
    fun matchesBundledRuntime(): Boolean =
        sampleRate == DeepFilterNetProcessor.SAMPLE_RATE &&
            fftSize == DeepFilterNetProcessor.FFT_SIZE &&
            hopSize == DeepFilterNetProcessor.HOP_SAMPLES &&
            nbErb == DeepFilterNetProcessor.NB_ERB &&
            nbDf == DeepFilterNetProcessor.NB_DF &&
            dfOrder > 0 &&
            convLookahead >= 0 &&
            dfLookahead >= 0

    companion object {
        fun parse(ini: String?): DfnModelConfig? {
            if (ini.isNullOrBlank()) return null
            val values = HashMap<String, String>()
            var section = ""
            for (raw in ini.lineSequence()) {
                val line = raw.substringBefore('#').substringBefore(';').trim()
                if (line.isEmpty()) continue
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length - 1).trim()
                    continue
                }
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                values["$section.$key"] = value
            }
            fun num(key: String): Int? = values[key]?.substringBefore(',')?.trim()?.toIntOrNull()
            return DfnModelConfig(
                sampleRate = num("df.sr") ?: return null,
                fftSize = num("df.fft_size") ?: return null,
                hopSize = num("df.hop_size") ?: return null,
                nbErb = num("df.nb_erb") ?: return null,
                nbDf = num("df.nb_df") ?: return null,
                dfOrder = num("df.df_order") ?: num("deepfilternet.df_order") ?: return null,
                convLookahead = num("deepfilternet.conv_lookahead") ?: return null,
                dfLookahead = num("df.df_lookahead") ?: num("deepfilternet.df_lookahead") ?: return null,
            )
        }
    }
}

/**
 * Fast mixed-radix (960 = 2 * 480 = 2 * 15 * 32) real-to-complex / complex-to-real FFT
 * matching `realfft`'s unnormalized forward / unnormalized inverse conventions used by `libDF`.
 */
internal class RealFft960 {
    private val n = 480 // Complex FFT size = 960 / 2
    private val r15 = 15
    private val r32 = 32

    private val cRe = FloatArray(n)
    private val cIm = FloatArray(n)
    private val tmpRe = FloatArray(n)
    private val tmpIm = FloatArray(n)

    // Precomputed twiddle tables for 15-point DFT, inter-stage twiddles, 32-point radix-2, and 960 real unpack.
    private val dft15Cos = FloatArray(r15 * r15)
    private val dft15Sin = FloatArray(r15 * r15)
    private val twiddleCos = FloatArray(n)
    private val twiddleSin = FloatArray(n)
    private val bitRev32 = IntArray(r32)
    private val r32Cos = FloatArray(r32)
    private val r32Sin = FloatArray(r32)
    private val unpackCos = FloatArray(n)
    private val unpackSin = FloatArray(n)

    init {
        for (k in 0 until r15) {
            for (m in 0 until r15) {
                val ang = -2.0 * PI * k * m / r15
                dft15Cos[k * r15 + m] = cos(ang).toFloat()
                dft15Sin[k * r15 + m] = sin(ang).toFloat()
            }
        }
        for (k1 in 0 until r15) {
            for (n2 in 0 until r32) {
                val ang = -2.0 * PI * k1 * n2 / n
                twiddleCos[k1 * r32 + n2] = cos(ang).toFloat()
                twiddleSin[k1 * r32 + n2] = sin(ang).toFloat()
            }
        }
        for (i in 0 until r32) {
            var rev = 0
            var v = i
            repeat(5) {
                rev = (rev shl 1) or (v and 1)
                v = v ushr 1
            }
            bitRev32[i] = rev
            val ang = -2.0 * PI * i / r32
            r32Cos[i] = cos(ang).toFloat()
            r32Sin[i] = sin(ang).toFloat()
        }
        for (k in 0 until n) {
            val ang = -PI * k / n
            unpackCos[k] = cos(ang).toFloat()
            unpackSin[k] = sin(ang).toFloat()
        }
    }

    fun forward(realIn: FloatArray, outRe: FloatArray, outIm: FloatArray) {
        for (i in 0 until n) {
            cRe[i] = realIn[i * 2]
            cIm[i] = realIn[i * 2 + 1]
        }
        complexFft480(cRe, cIm, inverse = false)
        outRe[0] = cRe[0] + cIm[0]
        outIm[0] = 0f
        outRe[n] = cRe[0] - cIm[0]
        outIm[n] = 0f
        for (k in 1 until n) {
            val nk = n - k
            val evenRe = 0.5f * (cRe[k] + cRe[nk])
            val evenIm = 0.5f * (cIm[k] - cIm[nk])
            val oddRe = 0.5f * (cIm[k] + cIm[nk])
            val oddIm = -0.5f * (cRe[k] - cRe[nk])
            val wr = unpackCos[k]
            val wi = unpackSin[k]
            val twOddRe = oddRe * wr - oddIm * wi
            val twOddIm = oddRe * wi + oddIm * wr
            outRe[k] = evenRe + twOddRe
            outIm[k] = evenIm + twOddIm
        }
    }

    fun inverse(inRe: FloatArray, inIm: FloatArray, realOut: FloatArray) {
        cRe[0] = inRe[0] + inRe[n]
        cIm[0] = inRe[0] - inRe[n]
        for (k in 1 until n) {
            val nk = n - k
            val evenRe = inRe[k] + inRe[nk]
            val evenIm = inIm[k] - inIm[nk]
            val diffRe = inRe[k] - inRe[nk]
            val diffIm = inIm[k] + inIm[nk]
            // Multiply by j * conj(W_960^k)
            val wr = unpackCos[k]
            val wi = -unpackSin[k]
            val oddRe = -(diffRe * wi + diffIm * wr)
            val oddIm = diffRe * wr - diffIm * wi
            cRe[k] = evenRe + oddRe
            cIm[k] = evenIm + oddIm
        }
        complexFft480(cRe, cIm, inverse = true)
        for (i in 0 until n) {
            realOut[i * 2] = cRe[i]
            realOut[i * 2 + 1] = cIm[i]
        }
    }

    private fun complexFft480(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val sign = if (inverse) -1f else 1f
        // Step 1: 32 DFTs of size 15 (stride 32), apply inter-stage twiddle.
        for (n2 in 0 until r32) {
            for (k1 in 0 until r15) {
                var sumRe = 0f
                var sumIm = 0f
                val row = k1 * r15
                for (n1 in 0 until r15) {
                    val idx = n1 * r32 + n2
                    val xr = re[idx]
                    val xi = im[idx]
                    val wr = dft15Cos[row + n1]
                    val wi = sign * dft15Sin[row + n1]
                    sumRe += xr * wr - xi * wi
                    sumIm += xr * wi + xi * wr
                }
                val twIdx = k1 * r32 + n2
                val twR = twiddleCos[twIdx]
                val twI = sign * twiddleSin[twIdx]
                val outIdx = k1 * r32 + bitRev32[n2]
                tmpRe[outIdx] = sumRe * twR - sumIm * twI
                tmpIm[outIdx] = sumRe * twI + sumIm * twR
            }
        }
        // Step 2: 15 radix-2 FFTs of size 32 in-place on tmpRe/tmpIm.
        for (k1 in 0 until r15) {
            val base = k1 * r32
            var len = 2
            while (len <= r32) {
                val half = len shr 1
                val step = r32 / len
                var i = 0
                while (i < r32) {
                    for (j in 0 until half) {
                        val tw = j * step
                        val wr = r32Cos[tw]
                        val wi = sign * r32Sin[tw]
                        val uIdx = base + i + j
                        val vIdx = uIdx + half
                        val vr = tmpRe[vIdx] * wr - tmpIm[vIdx] * wi
                        val vi = tmpRe[vIdx] * wi + tmpIm[vIdx] * wr
                        val ur = tmpRe[uIdx]
                        val ui = tmpIm[uIdx]
                        tmpRe[uIdx] = ur + vr
                        tmpIm[uIdx] = ui + vi
                        tmpRe[vIdx] = ur - vr
                        tmpIm[vIdx] = ui - vi
                    }
                    i += len
                }
                len = len shl 1
            }
        }
        // Step 3: Transpose [k1, k2] -> k = k2 * 15 + k1.
        for (k1 in 0 until r15) {
            val base = k1 * r32
            for (k2 in 0 until r32) {
                val outIdx = k2 * r15 + k1
                re[outIdx] = tmpRe[base + k2]
                im[outIdx] = tmpIm[base + k2]
            }
        }
    }
}
