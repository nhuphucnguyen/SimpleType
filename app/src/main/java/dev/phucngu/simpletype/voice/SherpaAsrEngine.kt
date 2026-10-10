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
 * [AsrEngine] backed by sherpa-onnx running an offline transducer ([SherpaModel]) fully
 * on-device via onnxruntime: the Vietnamese Zipformer or the English Parakeet TDT.
 *
 * Both are **offline** (non-causal) models, so they are not fed continuously like a true
 * streaming recognizer. Instead we gate them with Silero VAD: PCM frames are pushed to the
 * VAD, and whenever the VAD reports a completed speech segment we run the recognizer on that
 * segment and emit a single [AsrListener.onFinal]. Decoding is many times faster than real
 * time on CPU, so each phrase is transcribed within a fraction of a second of the user
 * pausing — the "VAD-gated near-real-time" model rather than word-by-word streaming.
 *
 * Model files ([SherpaModel.files]) are loaded from [modelDir] and the VAD from [vadModel];
 * both are installed by [ModelManager]. [punctuatorLoader], when given (Vietnamese), builds the
 * text punctuator used by [SherpaText.formatAfter]; if it fails, segments stay unpunctuated.
 * With a punctuator, each segment is punctuated together with the previous one of the session,
 * so a short pause can turn out to be a comma (or nothing) instead of a period.
 *
 * The recognizer/VAD are created lazily in [load] and reused across utterances. All methods
 * are invoked on the audio capture thread by [VoiceInputController]; decoding runs inline on
 * that thread (segments are short, so the brief blocking is acceptable for a keyboard).
 */
class SherpaAsrEngine(
    private val model: SherpaModel,
    private val modelDir: File,
    private val vadModel: File,
    private val confidence: Float = 0.95f,
    private val numThreads: Int = 2,
    private val punctuatorLoader: (() -> TextPunctuator)? = null,
) : AsrEngine {

    private var punctuator: TextPunctuator? = null
    private var punctuatorFailed = false

    /** Previous segment of this session (as emitted) and the VAD sample where it ended. */
    private var previousText: String? = null
    private var previousEnd = 0L

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var listener: AsrListener? = null

    override val name: String = model.dirName

    private fun file(child: String) = File(modelDir, child)

    override val isAvailable: Boolean
        get() = vadModel.exists() && model.files.all { file(it).exists() }

    override fun load(listener: AsrListener) {
        this.listener = listener

        if (vad == null) {
            vad = Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadModel.absolutePath,
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
                            encoder = file(model.encoder).absolutePath,
                            decoder = file(model.decoder).absolutePath,
                            joiner = file(model.joiner).absolutePath,
                        ),
                        tokens = file(model.tokens).absolutePath,
                        modelType = model.modelType,
                        numThreads = numThreads,
                        provider = "cpu",
                    ),
                    decodingMethod = "greedy_search",
                ),
            )
        }

        loadPunctuator()
        previousText = null
    }

    private fun loadPunctuator() {
        val loader = punctuatorLoader ?: return
        if (punctuator != null || punctuatorFailed) return
        try {
            val t0 = System.nanoTime()
            punctuator = loader()
            Log.i(TAG, "punctuator loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
        } catch (t: Throwable) {
            punctuatorFailed = true
            Log.w(TAG, "punctuation model failed to load", t)
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
            previousText = null // VAD sample positions restart after reset
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
            val start = segment.start.toLong()
            val previous = previousText?.takeIf { start - previousEnd <= MAX_JOIN_GAP }
            val out = decode(rec, segment.samples, previous)
            if (out.text.isEmpty()) continue
            previousText = out.text
            previousEnd = start + segment.samples.size
            listener?.onFinal(out.text, confidence, out.join)
        }
    }

    private fun decode(rec: OfflineRecognizer, samples: FloatArray, previous: String?): Segment {
        val stream = rec.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val raw = rec.getResult(stream).text
            val t0 = System.nanoTime()
            val out = SherpaText.formatAfter(raw, model, punctuator, previous)
            if (punctuator != null) {
                Log.d(TAG, "punctuated in ${(System.nanoTime() - t0) / 1_000_000} ms: ${out.join}")
            }
            out
        } finally {
            stream.release()
        }
    }

    companion object {
        private const val TAG = "SherpaAsrEngine"
        private const val SAMPLE_RATE = 16_000
        /** Longer silences than this always end the sentence (in samples: 2 s). */
        private const val MAX_JOIN_GAP = 2L * SAMPLE_RATE
    }
}
