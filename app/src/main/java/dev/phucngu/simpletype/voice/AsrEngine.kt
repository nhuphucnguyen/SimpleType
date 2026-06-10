package dev.phucngu.simpletype.voice

/**
 * Abstraction over an on-device speech-to-text engine.
 *
 * Per the product spec, recognition runs fully on-device and the active model is swapped
 * by language subtype (Whisper/PhoWhisper via whisper.cpp, with Vosk as a streaming
 * fallback). Hiding each behind this interface lets the IME stay engine-agnostic and lets
 * models be upgraded or swapped per language without touching the input pipeline.
 *
 * Lifecycle: [load] once when voice typing is first used for a language, [feed] 16 kHz
 * mono PCM frames while the user speaks, [endOfUtterance] when the VAD detects silence (to
 * flush a final result), and [release] to free native memory.
 */
interface AsrEngine {

    /** Identifier for diagnostics / model selection (e.g. "whisper-base.en"). */
    val name: String

    /** Whether the model file backing this engine is present and ready to [load]. */
    val isAvailable: Boolean

    /** Loads the model into memory. Safe to call repeatedly; subsequent calls are no-ops. */
    fun load()

    /**
     * Feed a chunk of 16 kHz mono 16-bit PCM samples. Streaming engines may emit
     * [AsrListener.onPartial] callbacks as audio arrives.
     */
    fun feed(samples: ShortArray, length: Int)

    /** Signal that the current utterance ended; flushes a final [AsrListener.onFinal]. */
    fun endOfUtterance()

    /** Frees native resources. The engine must be re-[load]ed before reuse. */
    fun release()
}

/** Callbacks delivered on the main thread by a [VoiceInputController]. */
interface AsrListener {
    /** Streaming hypothesis for the current utterance; shown as composing (underlined) text. */
    fun onPartial(text: String)
    /** Finalised utterance text with a [0,1] confidence used for command disambiguation. */
    fun onFinal(text: String, confidence: Float)
    fun onError(message: String)
}

/** The two languages SimpleType recognises in v1. */
enum class VoiceLanguage { ENGLISH, VIETNAMESE }
