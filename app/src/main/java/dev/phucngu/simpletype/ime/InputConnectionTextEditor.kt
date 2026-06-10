package dev.phucngu.simpletype.ime

import android.view.inputmethod.InputConnection
import dev.phucngu.simpletype.voice.TextEditor

/**
 * [TextEditor] backed by the IME's current [InputConnection].
 *
 * The connection is fetched lazily through [provider] on every call, since the IME hands out
 * a fresh `InputConnection` per input session and it may be null between sessions.
 */
class InputConnectionTextEditor(
    private val provider: () -> InputConnection?,
) : TextEditor {

    override fun textBeforeCursor(maxChars: Int): CharSequence =
        provider()?.getTextBeforeCursor(maxChars, 0) ?: ""

    override fun textAfterCursor(maxChars: Int): CharSequence =
        provider()?.getTextAfterCursor(maxChars, 0) ?: ""

    override fun commitText(text: CharSequence) {
        provider()?.commitText(text, 1)
    }

    override fun deleteBeforeCursor(charCount: Int) {
        if (charCount > 0) provider()?.deleteSurroundingText(charCount, 0)
    }

    override fun selectAll() {
        provider()?.performContextMenuAction(android.R.id.selectAll)
    }
}
