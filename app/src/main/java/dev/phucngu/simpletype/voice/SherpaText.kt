package dev.phucngu.simpletype.voice

/** Turns one raw VAD-segment transcription into natural keyboard text. */
object SherpaText {

    /**
     * Each segment is pause-delimited, so it approximates one sentence:
     *  - models with [SherpaModel.uppercaseOutput] (the vi Zipformer, e.g. "ÂM LƯỢNG TV GIẢM")
     *    are lowercased first (Unicode-aware, Ư→ư, Đ→đ); cased models (Parakeet) keep their
     *    casing so "I" and proper nouns survive,
     *  - the first letter is capitalised, and
     *  - a terminal period is added unless the model already ended the sentence.
     * There is no on-device Vietnamese punctuation model, so vi text never gets commas / ? / !.
     */
    fun format(raw: String, model: SherpaModel): String {
        val trimmed = raw.trim()
        val text = if (model.uppercaseOutput) trimmed.lowercase() else trimmed
        if (text.isEmpty()) return ""
        val cased = text.replaceFirstChar { it.uppercase() }
        return if (cased.last() in ".!?") cased else "$cased."
    }
}
