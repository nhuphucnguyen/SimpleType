package dev.phucngu.simpletype.voice

/**
 * Executes a matched [VoiceAction] against a [TextEditor] and maintains an undo buffer.
 *
 * Every mutation is recorded as a reversible [EditOp] (an insertion or a deletion of a
 * known string at the cursor), so the "undo" command — and so any false-positive command —
 * can be cleanly reverted, satisfying the spec's "every command is undoable" guarantee.
 *
 * Context disambiguation (spec rule 2) lives here: a destructive command issued on an empty
 * field falls back to committing the original utterance as text, since there is nothing to
 * act on. Pure logic, no Android dependencies — unit-testable via a fake [TextEditor].
 */
class VoiceCommandHandler(private val editor: TextEditor) {

    /** One reversible edit: text was inserted at, deleted from, or replaced before the cursor. */
    private sealed interface EditOp {
        /** [dictation]: a dictated segment, which the next segment may join onto. */
        data class Inserted(val text: String, val dictation: Boolean = false) : EditOp
        data class Deleted(val text: String) : EditOp
        data class Replaced(val removed: String, val inserted: String) : EditOp
    }

    private val undoStack = ArrayDeque<EditOp>()

    /** Outcome of handling an action, so the IME can react (e.g. stop listening). */
    enum class Result { HANDLED, STOP_LISTENING, NOTHING }

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    fun clearHistory() = undoStack.clear()

    /**
     * @param originalText the raw utterance, used as the fallback when a destructive command
     *   targets an empty field.
     * @param join how dictated text attaches to the segment dictated just before it.
     */
    fun handle(action: VoiceAction, originalText: String, join: SegmentJoin = SegmentJoin.NONE): Result = when (action) {
        is VoiceAction.DeleteWord ->
            if (fieldEmpty()) commitFallback(originalText) else { deleteLastWord(); Result.HANDLED }
        is VoiceAction.DeleteSentence ->
            if (fieldEmpty()) commitFallback(originalText) else { deleteLastSentence(); Result.HANDLED }
        is VoiceAction.NewLine -> { insert("\n"); Result.HANDLED }
        is VoiceAction.NewParagraph -> { insert("\n\n"); Result.HANDLED }
        is VoiceAction.SelectAll -> { editor.selectAll(); Result.HANDLED }
        is VoiceAction.Undo -> { undo(); Result.HANDLED }
        is VoiceAction.StopListening -> Result.STOP_LISTENING
        is VoiceAction.CommitText ->
            if (action.text.isEmpty()) Result.NOTHING else { insertDictation(action.text, join); Result.HANDLED }
    }

    // ---- Editing primitives ----

    private fun deleteLastWord() {
        val before = editor.textBeforeCursor(MAX_LOOKBACK).toString()
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--      // trailing whitespace
        while (i > 0 && !before[i - 1].isWhitespace()) i--      // the word itself
        deleteRange(before, i)
    }

    private fun deleteLastSentence() {
        val before = editor.textBeforeCursor(MAX_LOOKBACK).toString()
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && before[i - 1] !in SENTENCE_ENDS) i--
        deleteRange(before, i)
    }

    /** Deletes `before[from until end]` and records it for undo. */
    private fun deleteRange(before: String, from: Int) {
        val deleted = before.substring(from)
        if (deleted.isEmpty()) return
        editor.deleteBeforeCursor(deleted.length)
        undoStack.addLast(EditOp.Deleted(deleted))
    }

    private fun insert(text: String) {
        editor.commitText(text)
        undoStack.addLast(EditOp.Inserted(text))
    }

    /**
     * Commit dictated text with a leading space when it abuts a preceding word. A [join] onto
     * the previous segment rewrites that segment's final period, but only while the cursor
     * still sits right after it (nothing typed, moved or commanded since); otherwise the text
     * is committed as a new sentence.
     */
    private fun insertDictation(text: String, join: SegmentJoin = SegmentJoin.NONE) {
        if (join != SegmentJoin.NONE && joinPrevious(text, join)) return
        val prev = editor.textBeforeCursor(1).toString()
        val needSpace = prev.isNotEmpty() && !prev.last().isWhitespace() &&
            text.isNotEmpty() && text.first().isLetterOrDigit()
        val body = if (join == SegmentJoin.NONE) text else text.replaceFirstChar { it.uppercase() }
        editor.commitText(if (needSpace) " $body" else body)
        undoStack.addLast(EditOp.Inserted(if (needSpace) " $body" else body, dictation = true))
    }

    private fun joinPrevious(text: String, join: SegmentJoin): Boolean {
        val last = when (val op = undoStack.lastOrNull()) {
            is EditOp.Inserted -> op.text.takeIf { op.dictation }
            is EditOp.Replaced -> op.inserted // itself a joined dictated segment
            else -> null
        } ?: return false
        if (!last.endsWith(".") || editor.textBeforeCursor(last.length).toString() != last) return false
        val inserted = (if (join == SegmentJoin.COMMA) ", " else " ") + text
        editor.deleteBeforeCursor(1)
        editor.commitText(inserted)
        undoStack.addLast(EditOp.Replaced(".", inserted))
        return true
    }

    private fun undo() {
        when (val op = undoStack.removeLastOrNull()) {
            is EditOp.Inserted -> editor.deleteBeforeCursor(op.text.length)
            is EditOp.Deleted -> editor.commitText(op.text)
            is EditOp.Replaced -> {
                editor.deleteBeforeCursor(op.inserted.length)
                editor.commitText(op.removed)
            }
            null -> {}
        }
    }

    private fun commitFallback(text: String): Result {
        if (text.isEmpty()) return Result.NOTHING
        insertDictation(text)
        return Result.HANDLED
    }

    private fun fieldEmpty(): Boolean =
        editor.textBeforeCursor(1).isEmpty() && editor.textAfterCursor(1).isEmpty()

    companion object {
        private const val MAX_LOOKBACK = 1000
        private val SENTENCE_ENDS = charArrayOf('.', '!', '?', '\n')
    }
}
