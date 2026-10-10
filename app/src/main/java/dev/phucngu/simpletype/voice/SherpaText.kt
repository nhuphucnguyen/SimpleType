package dev.phucngu.simpletype.voice

import android.util.Log

/** Turns one raw VAD-segment transcription into natural keyboard text. */
object SherpaText {

    /**
     * Each segment is pause-delimited, so it approximates one sentence:
     *  - models with [SherpaModel.uppercaseOutput] (the vi Zipformer, e.g. "ÂM LƯỢNG TV GIẢM")
     *    are lowercased first (Unicode-aware, Ư→ư, Đ→đ); cased models (Parakeet) keep their
     *    casing so "I" and proper nouns survive,
     *  - for those uncased models, [punctuate] (the on-device Vietnamese dot/comma model,
     *    [dev.phucngu.simpletype.voice.punct.Punctuator]) inserts commas and in-segment
     *    periods; if it is missing or throws, the segment is left unpunctuated,
     *  - the first letter of every sentence is capitalised, and
     *  - a terminal period is added unless the text already ends a sentence.
     */
    fun format(raw: String, model: SherpaModel, punctuate: ((String) -> String)? = null): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        var text = trimmed
        if (model.uppercaseOutput) {
            text = text.lowercase()
            if (punctuate != null) {
                text = runCatching { punctuate(text) }
                    .onFailure { runCatching { Log.w(TAG, "punctuation failed", it) } }
                    .getOrDefault(text)
            }
            text = capitalizeSentences(text)
        }
        val cased = text.replaceFirstChar { it.uppercase() }
        return if (cased.last() in ".!?") cased else "$cased."
    }

    private val SENTENCE_START = Regex("([.!?]\\s+)(\\p{L})")

    private fun capitalizeSentences(text: String): String =
        SENTENCE_START.replace(text) { it.groupValues[1] + it.groupValues[2].uppercase() }

    private const val TAG = "SherpaText"
}
