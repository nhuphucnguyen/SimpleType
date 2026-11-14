package dev.phucngu.simpletype.ime

import dev.phucngu.simpletype.text.TelexEngine
import dev.phucngu.simpletype.text.lastGraphemeLength

/**
 * A small text buffer the keyboard types into instead of the app's field, used for the AI
 * writer's request. Letters go through Telex when typing Vietnamese, as they would in a field.
 */
class PromptBuffer {
    private val text = StringBuilder()
    private val telex = TelexEngine()

    val value: String get() = text.toString() + telex.composing

    val isBlank: Boolean get() = value.isBlank()

    fun type(c: Char, vietnamese: Boolean) {
        if (vietnamese && c.isLetter()) {
            telex.input(c)
        } else {
            settle()
            text.append(c)
        }
    }

    fun backspace() {
        if (!telex.isEmpty) {
            telex.backspace()
            return
        }
        if (text.isEmpty()) return
        text.setLength(text.length - lastGraphemeLength(text).coerceAtLeast(1))
    }

    /** Deletes back to the start of the previous word. */
    fun deleteWord() {
        settle()
        var i = text.length
        while (i > 0 && text[i - 1].isWhitespace()) i--
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        text.setLength(i)
    }

    /** Appends a whole phrase (dictation, a glided word), separated from what is there by a space. */
    fun appendWords(words: String) {
        val w = words.trim()
        if (w.isEmpty()) return
        settle()
        if (text.isNotEmpty() && !text.last().isWhitespace()) text.append(' ')
        text.append(w)
    }

    fun set(value: String) {
        clear()
        text.append(value)
    }

    fun clear() {
        text.setLength(0)
        telex.reset()
    }

    /** Ends the Telex word in progress, keeping its letters. */
    private fun settle() {
        text.append(telex.composing)
        telex.reset()
    }
}
