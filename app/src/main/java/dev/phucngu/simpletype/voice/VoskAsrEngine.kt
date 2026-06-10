package dev.phucngu.simpletype.voice

import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * [AsrEngine] backed by Vosk — a lightweight, fully on-device streaming recognizer
 * (Apache-2.0). The model lives in [modelPath]; [isAvailable] is a cheap file check so the
 * IME can show a "model not installed" hint without loading anything.
 *
 * Streaming: each fed PCM chunk goes to the recognizer; an internal endpoint (silence)
 * makes `acceptWaveForm` return true, at which point a final result is emitted, otherwise a
 * partial hypothesis. The model/recognizer are loaded once and reused across utterances.
 */
class VoskAsrEngine(
    private val modelPath: String,
    override val name: String,
    private val confidence: Float = 0.9f,
) : AsrEngine {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var listener: AsrListener? = null

    override val isAvailable: Boolean
        get() = File(modelPath, "am").exists() || File(modelPath, "conf").exists()

    override fun load(listener: AsrListener) {
        this.listener = listener
        val m = model ?: Model(modelPath).also { model = it }
        if (recognizer == null) {
            recognizer = Recognizer(m, SAMPLE_RATE)
        } else {
            recognizer?.reset()
        }
    }

    override fun feed(samples: ShortArray, length: Int) {
        val rec = recognizer ?: return
        try {
            if (rec.acceptWaveForm(samples, length)) {
                emitFinal(VoskResult.finalText(rec.result))
            } else {
                val partial = VoskResult.partialText(rec.partialResult)
                if (partial.isNotEmpty()) listener?.onPartial(partial)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "acceptWaveForm failed", t)
        }
    }

    override fun endOfUtterance() {
        val rec = recognizer ?: return
        emitFinal(VoskResult.finalText(rec.finalResult))
        rec.reset()
    }

    override fun release() {
        recognizer?.close()
        model?.close()
        recognizer = null
        model = null
        listener = null
    }

    private fun emitFinal(text: String) {
        if (text.isNotEmpty()) listener?.onFinal(text, confidence)
    }

    companion object {
        private const val TAG = "VoskAsrEngine"
        private const val SAMPLE_RATE = 16_000f
    }
}
