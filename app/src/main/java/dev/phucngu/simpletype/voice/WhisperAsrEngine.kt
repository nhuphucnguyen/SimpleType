package dev.phucngu.simpletype.voice

import android.util.Log
import java.io.File

/**
 * [AsrEngine] backed by whisper.cpp (PhoWhisper for Vietnamese). Unlike the streaming Vosk
 * engine, Whisper is an *utterance* recogniser: fed PCM is accumulated while the user speaks and
 * decoded in one pass on [endOfUtterance], which emits a single [AsrListener.onFinal].
 *
 * The model is a ggml file at [modelPath]; [isAvailable] requires both the native library
 * ([WhisperLib.available]) and that file, so in NDK-free builds — or before the model is
 * installed — the engine reports unavailable and the caller can fall back to Vosk.
 *
 * The native context is loaded lazily on first [load] and reused across utterances; [release]
 * frees it.
 */
class WhisperAsrEngine(
    private val modelPath: String,
    override val name: String,
    private val language: String = "vi",
    private val confidence: Float = 0.9f,
) : AsrEngine {

    private var contextPtr: Long = 0L
    private var listener: AsrListener? = null

    /** Accumulated 16 kHz mono PCM16 frames for the current utterance. */
    private val chunks = ArrayList<ShortArray>()
    private var sampleCount = 0

    private val threads: Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    override val isAvailable: Boolean
        get() = WhisperLib.available && File(modelPath).isFile

    override fun load(listener: AsrListener) {
        this.listener = listener
        resetBuffer()
        if (contextPtr == 0L && isAvailable) {
            contextPtr = try {
                WhisperLib.initContext(modelPath)
            } catch (t: Throwable) {
                Log.w(TAG, "initContext failed", t)
                0L
            }
            if (contextPtr == 0L) listener.onError("model_load_failed")
        }
    }

    override fun feed(samples: ShortArray, length: Int) {
        if (contextPtr == 0L) return
        if (sampleCount >= MAX_SAMPLES) return // cap runaway utterances (~120 s)
        chunks.add(samples.copyOf(length))
        sampleCount += length
    }

    override fun endOfUtterance() {
        val ctx = contextPtr
        if (ctx == 0L || sampleCount == 0) {
            resetBuffer()
            return
        }
        val audio = toFloatPcm()
        resetBuffer()
        val text = try {
            WhisperLib.transcribe(ctx, threads, language, audio).trim()
        } catch (t: Throwable) {
            Log.w(TAG, "transcribe failed", t)
            ""
        }
        if (text.isNotEmpty()) listener?.onFinal(text, confidence)
    }

    override fun release() {
        if (contextPtr != 0L) {
            WhisperLib.freeContext(contextPtr)
            contextPtr = 0L
        }
        resetBuffer()
        listener = null
    }

    /** Flattens the accumulated PCM16 frames into one float[-1, 1] array for whisper.cpp. */
    private fun toFloatPcm(): FloatArray {
        val out = FloatArray(sampleCount)
        var i = 0
        for (chunk in chunks) {
            for (s in chunk) {
                out[i++] = s / 32768f
            }
        }
        return out
    }

    private fun resetBuffer() {
        chunks.clear()
        sampleCount = 0
    }

    companion object {
        private const val TAG = "WhisperAsrEngine"
        private const val SAMPLE_RATE = 16_000
        private const val MAX_SAMPLES = SAMPLE_RATE * 120
    }
}
