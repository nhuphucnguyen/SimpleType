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

    /**
     * Formats a segment spoken right after [previous] (the previous segment of the same
     * session, as committed). For uncased models with a [punctuator], the model judges the
     * pause between the two: [Segment.join] says whether the previous segment's period should
     * become a comma ([SegmentJoin.COMMA]) or go away ([SegmentJoin.CONTINUE]); the text then
     * starts lowercase. Otherwise this is [format] with [SegmentJoin.NONE].
     */
    fun formatAfter(raw: String, model: SherpaModel, punctuator: TextPunctuator?, previous: String?): Segment {
        val standalone = { Segment(format(raw, model, punctuator?.let { it::punctuate }), SegmentJoin.NONE) }
        val trimmed = raw.trim()
        if (!model.uppercaseOutput || punctuator == null || previous.isNullOrBlank() || trimmed.isEmpty()) {
            return standalone()
        }
        val c = runCatching { punctuator.continueAfter(previous, trimmed.lowercase()) }
            .onFailure { runCatching { Log.w(TAG, "punctuation failed", it) } }
            .getOrNull() ?: return standalone()
        val join = when (c.boundary) {
            ',' -> SegmentJoin.COMMA
            null -> SegmentJoin.CONTINUE
            else -> SegmentJoin.NONE
        }
        var text = capitalizeSentences(c.text.trim())
        if (join == SegmentJoin.NONE) text = text.replaceFirstChar { it.uppercase() }
        return Segment(if (text.last() in ".!?") text else "$text.", join)
    }

    private val SENTENCE_START = Regex("([.!?]\\s+)(\\p{L})")

    private fun capitalizeSentences(text: String): String =
        SENTENCE_START.replace(text) { it.groupValues[1] + it.groupValues[2].uppercase() }

    private const val TAG = "SherpaText"
}
