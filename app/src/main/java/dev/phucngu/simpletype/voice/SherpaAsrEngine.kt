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
 * both are installed by [ModelManager].
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
) : AsrEngine {

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
            SherpaText.format(rec.getResult(stream).text, model)
        } finally {
            stream.release()
        }
    }

    companion object {
        private const val TAG = "SherpaAsrEngine"
        private const val SAMPLE_RATE = 16_000
    }
}
