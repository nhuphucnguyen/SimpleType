package dev.phucngu.simpletype.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives the on-device voice pipeline:
 *
 *   AudioRecord (16 kHz mono PCM) → [AsrEngine] → [AsrListener]
 *
 * Capture runs on a dedicated thread; the engine is fed raw PCM and pushes partial/final
 * results, which this controller marshals back to the main thread for the IME. Streaming
 * engines (Vosk) endpoint utterances internally; [stop] flushes a final via
 * [AsrEngine.endOfUtterance]. Media playback is ducked while listening, per the spec's
 * audio-focus requirement.
 *
 * The active [AsrEngine] is injected via [setEngine] so the same controller serves Vosk,
 * Whisper (EN) or PhoWhisper (VI). When the engine is unavailable (no model yet), [start]
 * reports an error instead of capturing audio.
 */
class VoiceInputController(
    private val context: Context,
    private val listener: AsrListener,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var engine: AsrEngine = NoopAsrEngine()

    /** Forwards engine callbacks (fired on the audio thread) to the main thread. */
    private val mainThreadListener = object : AsrListener {
        override fun onPartial(text: String) = post { listener.onPartial(text) }
        override fun onFinal(text: String, confidence: Float) =
            post { listener.onFinal(text, confidence) }
        override fun onError(message: String) = post { listener.onError(message) }
    }

    val isListening: Boolean get() = running.get()

    fun setEngine(engine: AsrEngine) {
        this.engine = engine
    }

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Begin listening. No-op if already listening. */
    fun start() {
        if (running.get()) return
        if (!engine.isAvailable) {
            listener.onError(ENGINE_UNAVAILABLE)
            return
        }
        if (!hasAudioPermission()) {
            listener.onError(NEEDS_PERMISSION)
            return
        }
        running.set(true)
        requestAudioFocus()
        captureThread = Thread({ captureLoop() }, "SimpleType-Audio").also { it.start() }
    }

    /** Stop listening and flush a final result. Safe to call when not listening. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        captureThread = null
        abandonAudioFocus()
    }

    /** Frees the active engine's native resources. Call when the IME is destroyed. */
    fun release() {
        stop()
        engine.release()
    }

    @SuppressLint("MissingPermission") // guarded by hasAudioPermission() in start()
    private fun captureLoop() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufferSize = maxOf(minBuf, FRAME_SAMPLES * 2 * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL, ENCODING, bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            mainThreadListener.onError(CAPTURE_FAILED)
            running.set(false)
            return
        }

        try {
            engine.load(mainThreadListener)
        } catch (t: Throwable) {
            record.release()
            mainThreadListener.onError(CAPTURE_FAILED)
            running.set(false)
            return
        }

        val frame = ShortArray(FRAME_SAMPLES)
        record.startRecording()
        try {
            while (running.get()) {
                val read = record.read(frame, 0, frame.size)
                if (read > 0) engine.feed(frame, read)
            }
        } finally {
            record.stop()
            record.release()
            engine.endOfUtterance()
        }
    }

    private fun requestAudioFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        @Suppress("DEPRECATION")
        am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
    }

    private fun abandonAudioFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        @Suppress("DEPRECATION")
        am.abandonAudioFocus(null)
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    companion object {
        const val ENGINE_UNAVAILABLE = "engine_unavailable"
        const val NEEDS_PERMISSION = "needs_permission"
        const val CAPTURE_FAILED = "capture_failed"

        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SAMPLES = 1600 // 100 ms at 16 kHz — Vosk-friendly chunk
    }
}
