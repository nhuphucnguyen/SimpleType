package dev.phucngu.simpletype.predict

/**
 * What the text before the cursor says about the word being typed: the word in progress
 * ([partial], empty right after a space) and up to two finished words before it.
 *
 * [previous] is oldest first. [sentenceStart] means nothing but a sentence boundary (start
 * of field, newline, . ? !) precedes [previous], so the model can condition on it.
 */
data class TypingContext(
    val previous: List<String>,
    val partial: String,
    val sentenceStart: Boolean,
) {
    companion object {
        private const val MAX_PREVIOUS = 2
        private const val SENTENCE_END = ".?!…\n"
        /** Separators that keep the words on either side in one phrase. */
        private const val SOFT_BREAK = " \t,;:\"“”()"

        fun isWordChar(c: Char) = c.isLetter() || c == '\'' || c == '’'

        /**
         * Parses [textBeforeCursor]. [complete] says the text reaches back to the start of the
         * field (it was shorter than requested), so running out of text is a sentence start.
         * Returns null when the cursor is inside something that is not a word (a number, a
         * URL, a handle), where word suggestions would only get in the way.
         */
        fun parse(textBeforeCursor: CharSequence, complete: Boolean): TypingContext? {
            val text = textBeforeCursor
            var end = text.length
            var start = end
            while (start > 0 && isWordChar(text[start - 1])) start--
            if (start > 0 && !isBoundary(text[start - 1])) return null
            val partial = text.substring(start, end).replace('’', '\'')

            val previous = ArrayList<String>(MAX_PREVIOUS)
            var sentenceStart = false
            end = start
            while (previous.size < MAX_PREVIOUS) {
                var i = end
                var sawSentenceEnd = false
                while (i > 0 && !isWordChar(text[i - 1])) {
                    val c = text[i - 1]
                    when {
                        c in SENTENCE_END -> sawSentenceEnd = true
                        c !in SOFT_BREAK -> return TypingContext(previous.reversed(), partial, false)
                    }
                    i--
                }
                if (sawSentenceEnd || (i == 0 && complete)) {
                    sentenceStart = true
                    break
                }
                if (i == 0) break
                val wordEnd = i
                while (i > 0 && isWordChar(text[i - 1])) i--
                if (i > 0 && !isBoundary(text[i - 1])) break
                previous.add(text.substring(i, wordEnd).replace('’', '\''))
                end = i
            }
            return TypingContext(previous.reversed(), partial, sentenceStart)
        }

        private fun isBoundary(c: Char) = c in SENTENCE_END || c in SOFT_BREAK
    }
}
