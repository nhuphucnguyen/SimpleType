package dev.phucngu.simpletype.voice

/**
 * Placeholder engine used until a real model (Whisper / PhoWhisper via whisper.cpp) is
 * bundled in M2/M3. It reports itself unavailable so the IME surfaces a "model not
 * installed" hint rather than silently failing, while keeping the pipeline wired up.
 */
class NoopAsrEngine(override val name: String = "noop") : AsrEngine {
    override val isAvailable: Boolean = false
    override fun load(listener: AsrListener) {}
    override fun feed(samples: ShortArray, length: Int) {}
    override fun endOfUtterance() {}
    override fun release() {}
}
