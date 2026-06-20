package dev.phucngu.simpletype.voice

/**
 * Pure, dependency-free audio helpers for the sherpa-onnx pipeline.
 *
 * Kept separate from [SherpaAsrEngine] so the numeric conversion logic is unit-testable on
 * the JVM without the native `sherpa-onnx-jni` library or any model files.
 */
internal object SherpaAudio {

    /** Full-scale value of signed 16-bit PCM; used to normalise to the [-1, 1) float range. */
    const val PCM16_FULL_SCALE = 32768.0f

    /**
     * Convert [length] signed 16-bit PCM samples to the normalised `float` array sherpa-onnx
     * expects (`OfflineStream.acceptWaveform` / `Vad.acceptWaveform`). Values are scaled to
     * roughly [-1, 1). [length] may be smaller than `samples.size` (a short final read).
     */
    fun toFloat(samples: ShortArray, length: Int): FloatArray {
        val n = length.coerceIn(0, samples.size)
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = samples[i] / PCM16_FULL_SCALE
        }
        return out
    }
}
