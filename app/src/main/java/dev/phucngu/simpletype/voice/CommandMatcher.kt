package dev.phucngu.simpletype.voice

/** A recognised editing command, or literal text to commit. */
sealed interface VoiceAction {
    data object DeleteWord : VoiceAction
    data object DeleteSentence : VoiceAction
    data object NewLine : VoiceAction
    data object NewParagraph : VoiceAction
    data object Undo : VoiceAction
    data object SelectAll : VoiceAction
    data object StopListening : VoiceAction
    /** Commit the given text verbatim (normal dictation, or the "type <words>" escape hatch). */
    data class CommitText(val text: String) : VoiceAction
}

/**
 * Matches a finalised utterance against the v1 voice-command grammar (EN + VI).
 *
 * Per the spec, an utterance is a command only if the **entire** normalised utterance is a
 * command phrase (utterances are pause-delimited by the VAD). Anything else — including the
 * "type <words>" / "gõ <words>" escape hatch — becomes literal [VoiceAction.CommitText].
 * Low-confidence matches are also committed as text so false positives stay cheap.
 *
 * Pure logic, no Android dependencies, so it is unit-testable.
 */
class CommandMatcher(private val confidenceThreshold: Float = 0.5f) {

    fun match(rawUtterance: String, confidence: Float = 1f): VoiceAction {
        val text = rawUtterance.trim()
        if (text.isEmpty()) return VoiceAction.CommitText("")

        val normalized = normalize(text)

        // Escape hatch first so "type delete that" commits the words literally.
        val firstToken = text.trimStart().substringBefore(' ').lowercase().trim { it in ".,!?;:" }
        if (firstToken in TYPE_PREFIXES) {
            val rest = text.trimStart().substringAfter(' ', "").trim()
            return VoiceAction.CommitText(rest)
        }

        val action = GRAMMAR[normalized]
        return if (action != null && confidence >= confidenceThreshold) {
            action
        } else {
            VoiceAction.CommitText(text)
        }
    }

    /** Lowercase, strip surrounding punctuation, collapse internal whitespace. */
    private fun normalize(s: String): String =
        s.lowercase()
            .replace(PUNCT, " ")
            .trim()
            .replace(WHITESPACE, " ")

    companion object {
        private val PUNCT = Regex("[\\p{Punct}]")
        private val WHITESPACE = Regex("\\s+")

        private val TYPE_PREFIXES = listOf("type", "gõ", "go")

        /** Normalised command phrase → action, for English and Vietnamese equivalents. */
        private val GRAMMAR: Map<String, VoiceAction> = buildMap {
            fun bind(action: VoiceAction, vararg phrases: String) =
                phrases.forEach { put(it, action) }

            bind(VoiceAction.DeleteWord,
                "delete that", "delete last word", "delete word",
                "xóa từ", "xoá từ", "xóa từ cuối")
            bind(VoiceAction.DeleteSentence,
                "delete last sentence", "delete sentence",
                "xóa câu", "xoá câu", "xóa câu cuối")
            bind(VoiceAction.NewLine,
                "new line", "xuống dòng")
            bind(VoiceAction.NewParagraph,
                "new paragraph", "đoạn mới")
            bind(VoiceAction.Undo,
                "undo", "hoàn tác", "hủy")
            bind(VoiceAction.SelectAll,
                "select all", "chọn tất cả", "chọn hết")
            bind(VoiceAction.StopListening,
                "stop listening", "stop", "dừng", "dừng nghe", "tắt")
        }
    }
}
