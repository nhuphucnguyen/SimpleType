package dev.phucngu.simpletype.voice

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * [AsrEngine] backed by sherpa-onnx running a Vietnamese Zipformer transducer
 * (`sherpa-onnx-zipformer-vi-30M-int8-2026-02-09`) fully on-device via onnxruntime.
 *
 * This Zipformer is an **offline** (non-causal) model, so it is not fed continuously like a
 * true streaming recognizer. Instead we gate it with Silero VAD: PCM frames are pushed to the
 * VAD, and whenever the VAD reports a completed speech segment we run the recognizer on that
 * segment and emit a single [AsrListener.onFinal]. Decoding is ~10x faster than real time on
 * CPU, so each phrase is transcribed within a fraction of a second of the user pausing — the
 * "VAD-gated near-real-time" model rather than word-by-word streaming.
 *
 * Model files are expected as absolute files under [modelDir] (provisioned out-of-band, see
 * scripts/fetch-sherpa-vi-model.sh):
 *   encoder.int8.onnx, decoder.onnx, joiner.int8.onnx, tokens.txt, silero_vad.onnx
 *
 * The recognizer/VAD are created lazily in [load] and reused across utterances. All methods
 * are invoked on the audio capture thread by [VoiceInputController]; decoding runs inline on
 * that thread (segments are short, so the brief blocking is acceptable for a keyboard).
 *
 * Note: the bundled model is CC-BY-NC-ND-4.0 (non-commercial, no-derivatives). Fine for
 * research/personal builds; revisit before shipping commercially.
 */
class SherpaAsrEngine(
    private val modelDir: String,
    override val name: String,
    private val confidence: Float = 0.95f,
    private val numThreads: Int = 2,
) : AsrEngine {

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var listener: AsrListener? = null

    private fun file(child: String) = File(modelDir, child)

    override val isAvailable: Boolean
        get() = REQUIRED_FILES.all { file(it).exists() }

    override fun load(listener: AsrListener) {
        this.listener = listener

        if (vad == null) {
            vad = Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = file(SILERO_VAD).absolutePath,
                        threshold = 0.5f,
                        minSilenceDuration = 0.25f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = 8.0f,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                ),
            )
        } else {
            vad?.reset()
            vad?.clear()
        }

        if (recognizer == null) {
            recognizer = OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = file(ENCODER).absolutePath,
                            decoder = file(DECODER).absolutePath,
                            joiner = file(JOINER).absolutePath,
                        ),
                        tokens = file(TOKENS).absolutePath,
                        modelType = "transducer",
                        numThreads = numThreads,
                        provider = "cpu",
                    ),
                    decodingMethod = "greedy_search",
                ),
            )
        }
    }

    override fun feed(samples: ShortArray, length: Int) {
        val v = vad ?: return
        try {
            v.acceptWaveform(SherpaAudio.toFloat(samples, length))
            drainSegments()
        } catch (t: Throwable) {
            Log.w(TAG, "feed failed", t)
        }
    }

    override fun endOfUtterance() {
        val v = vad ?: return
        try {
            v.flush()
            drainSegments()
            v.reset()
            v.clear()
        } catch (t: Throwable) {
            Log.w(TAG, "endOfUtterance failed", t)
        }
    }

    override fun release() {
        recognizer?.release()
        vad?.release()
        recognizer = null
        vad = null
        listener = null
    }

    /** Decode every completed VAD speech segment and emit its transcription as a final. */
    private fun drainSegments() {
        val v = vad ?: return
        val rec = recognizer ?: return
        while (!v.empty()) {
            val segment = v.front()
            v.pop()
            val text = decode(rec, segment.samples)
            if (text.isNotEmpty()) listener?.onFinal(text, confidence)
        }
    }

    private fun decode(rec: OfflineRecognizer, samples: FloatArray): String {
        val stream = rec.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            formatUtterance(rec.getResult(stream).text)
        } finally {
            stream.release()
        }
    }

    /**
     * Turn one raw VAD-segment result into natural keyboard text. The vi Zipformer emits
     * uppercase, punctuation-free words (e.g. "ÂM LƯỢNG TV GIẢM"), so we:
     *  - lowercase (Unicode-aware, handles Vietnamese diacritics Ư→ư, Đ→đ),
     *  - capitalize the first letter, and
     *  - append a terminal period.
     * Each segment is pause-delimited, so it approximates one sentence. No real punctuation
     * model exists on-device for Vietnamese, hence this heuristic (no commas / ? / !).
     */
    private fun formatUtterance(raw: String): String {
        val text = raw.trim().lowercase()
        if (text.isEmpty()) return ""
        val cased = text.replaceFirstChar { it.uppercase() }
        return if (cased.last() in ".!?") cased else "$cased."
    }

    companion object {
        private const val TAG = "SherpaAsrEngine"
        private const val SAMPLE_RATE = 16_000

        const val ENCODER = "encoder.int8.onnx"
        const val DECODER = "decoder.onnx"
        const val JOINER = "joiner.int8.onnx"
        const val TOKENS = "tokens.txt"
        const val SILERO_VAD = "silero_vad.onnx"

        val REQUIRED_FILES = listOf(ENCODER, DECODER, JOINER, TOKENS, SILERO_VAD)
    }
}
