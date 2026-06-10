package dev.phucngu.simpletype.voice

/**
 * Voice-activity detector. Production uses Silero VAD; this interface lets the audio
 * pipeline endpoint utterances (speech start / silence end) independently of the model.
 */
interface Vad {
    /**
     * Process one frame of 16 kHz mono PCM and return whether it contains speech.
     * The controller uses a run of non-speech frames to mark end-of-utterance.
     */
    fun isSpeech(samples: ShortArray, length: Int): Boolean

    fun reset()
}

/**
 * Trivial energy-threshold VAD used until the Silero model is bundled (M2). It keeps the
 * audio pipeline exercisable end-to-end without native dependencies.
 */
class EnergyVad(private val threshold: Double = 0.015) : Vad {
    override fun isSpeech(samples: ShortArray, length: Int): Boolean {
        if (length == 0) return false
        var sum = 0.0
        for (i in 0 until length) {
            val s = samples[i] / 32768.0
            sum += s * s
        }
        val rms = Math.sqrt(sum / length)
        return rms >= threshold
    }

    override fun reset() {}
}
