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
     * pause between the two: [Segment.join] says whether the previous segment's final mark
     * should become a comma ([SegmentJoin.COMMA]) or go away ([SegmentJoin.CONTINUE]), and the
     * text then starts lowercase (never for a question mark, which stays); or whether it should switch between `.` and `?`
     * ([SegmentJoin.QUESTION], [SegmentJoin.PERIOD]). Otherwise this is [format] with
     * [SegmentJoin.NONE].
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
        val prevMark = previous.trimEnd().lastOrNull()
        val join = when (c.boundary) {
            // a question mark already shown stays: after a real pause the speaker ended the
            // question ("Anh rảnh không? Em nhờ…"), even where one breath would take a comma
            ',' -> if (prevMark == '?') SegmentJoin.NONE else SegmentJoin.COMMA
            null -> if (prevMark == '?') SegmentJoin.NONE else SegmentJoin.CONTINUE
            '?' -> if (prevMark == '?') SegmentJoin.NONE else SegmentJoin.QUESTION
            else -> if (prevMark == '?') SegmentJoin.PERIOD else SegmentJoin.NONE
        }
        var text = capitalizeSentences(c.text.trim())
        if (join != SegmentJoin.COMMA && join != SegmentJoin.CONTINUE) {
            text = text.replaceFirstChar { it.uppercase() }
        }
        return Segment(if (text.last() in ".!?") text else "$text.", join)
    }

    private val SENTENCE_START = Regex("([.!?]\\s+)(\\p{L})")

    private fun capitalizeSentences(text: String): String =
        SENTENCE_START.replace(text) { it.groupValues[1] + it.groupValues[2].uppercase() }

    private const val TAG = "SherpaText"
}
