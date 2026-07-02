package dev.phucngu.simpletype.gesture

/** Pure text rules for committing a gesture-typed word (auto-spacing + capitalization). */
object GestureCommit {

    /** Characters after which no automatic space is inserted. */
    private const val NO_SPACE_AFTER = " \n\t([{\"'‘“/@#-_"

    /**
     * The exact text to commit for a decoded [word]: capitalized when [capitalize] is set,
     * prefixed with a space when the character before the cursor requires one (so swiping
     * consecutive words needs no manual spaces, like Gboard).
     */
    fun textToCommit(word: String, textBeforeCursor: CharSequence?, capitalize: Boolean): String {
        val result = if (capitalize) {
            word.replaceFirstChar { it.uppercaseChar() }
        } else {
            word
        }
        return if (needsLeadingSpace(textBeforeCursor)) " $result" else result
    }

    fun needsLeadingSpace(textBeforeCursor: CharSequence?): Boolean {
        if (textBeforeCursor.isNullOrEmpty()) return false
        return textBeforeCursor.last() !in NO_SPACE_AFTER
    }

    /** Restyles [replacement] to match the capitalization of the previously committed word. */
    fun matchCapitalization(previous: String, replacement: String): String =
        if (previous.isNotEmpty() && previous[0].isUpperCase()) {
            replacement.replaceFirstChar { it.uppercaseChar() }
        } else {
            replacement
        }
}
