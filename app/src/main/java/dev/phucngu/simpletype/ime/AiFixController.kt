package dev.phucngu.simpletype.ime

import android.view.inputmethod.InputConnection
import dev.phucngu.simpletype.ai.AiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drives the toolbar's AI-fix button: sends the selection (or the whole field when nothing is
 * selected) to [fixer], swaps in the result, and keeps the original for a one-tap undo.
 *
 * After a fix the cursor sits right after the fixed text, so undo only needs to check that
 * text is still there before swapping the original back in. Any other edit by the user
 * ([invalidate]) drops the undo.
 */
class AiFixController(
    private val scope: CoroutineScope,
    private val connection: () -> InputConnection?,
    /** Settle any in-progress composition (e.g. Telex) so the field holds plain text. */
    private val prepare: (InputConnection) -> Unit,
    private val fixer: suspend (String) -> String,
    private val onStateChange: (State) -> Unit,
    private val onOutcome: (Outcome) -> Unit,
) {
    enum class State { IDLE, WORKING, UNDOABLE }

    sealed interface Outcome {
        data object Fixed : Outcome
        data object Unchanged : Outcome
        data object Empty : Outcome
        /** The field changed while the model was working, so the result was dropped. */
        data object Stale : Outcome
        data object Undone : Outcome
        /** The fixed text was edited or the cursor moved away, so undo had nothing to revert. */
        data object UndoUnavailable : Outcome
        data class Failed(val error: AiException) : Outcome
    }

    /** What was sent: the text around the cursor plus the selection, as read before the call. */
    private data class Snapshot(val before: String, val selected: String, val after: String) {
        val hasSelection get() = selected.isNotEmpty()
        val target get() = if (hasSelection) selected else before + after
    }

    private data class Undo(val original: String, val fixed: String)

    var state = State.IDLE
        private set(value) {
            field = value
            onStateChange(value)
        }

    private var job: Job? = null
    private var undo: Undo? = null

    /** Toolbar tap: start a fix, cancel the one in flight, or undo the last one. */
    fun onButton() = when (state) {
        State.IDLE -> start()
        State.WORKING -> cancel()
        State.UNDOABLE -> undoLast()
    }

    /** The user edited the field some other way; the last fix can no longer be undone. */
    fun invalidate() {
        if (state == State.UNDOABLE) {
            undo = null
            state = State.IDLE
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        if (state == State.WORKING) state = State.IDLE
    }

    private fun start() {
        val ic = connection() ?: return
        prepare(ic)
        val snap = snapshot(ic)
        if (snap.target.isBlank()) {
            onOutcome(Outcome.Empty)
            return
        }
        state = State.WORKING
        job = scope.launch {
            val fixed = try {
                fixer(snap.target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                state = State.IDLE
                onOutcome(Outcome.Failed(e))
                return@launch
            }
            apply(snap, fixed)
        }
    }

    private fun apply(snap: Snapshot, fixed: String) {
        val ic = connection()
        if (ic == null) {
            state = State.IDLE
            return
        }
        prepare(ic)
        if (snapshot(ic) != snap) {
            state = State.IDLE
            onOutcome(Outcome.Stale)
            return
        }
        if (fixed == snap.target) {
            state = State.IDLE
            onOutcome(Outcome.Unchanged)
            return
        }
        ic.beginBatchEdit()
        if (!snap.hasSelection) ic.deleteSurroundingText(snap.before.length, snap.after.length)
        ic.commitText(fixed, 1) // replaces the selection, if any
        ic.endBatchEdit()
        undo = Undo(snap.target, fixed)
        state = State.UNDOABLE
        onOutcome(Outcome.Fixed)
    }

    private fun undoLast() {
        val u = undo ?: return
        undo = null
        state = State.IDLE
        val ic = connection() ?: return
        prepare(ic)
        if (ic.getTextBeforeCursor(u.fixed.length, 0)?.toString() != u.fixed) {
            onOutcome(Outcome.UndoUnavailable)
            return
        }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(u.fixed.length, 0)
        ic.commitText(u.original, 1)
        ic.endBatchEdit()
        onOutcome(Outcome.Undone)
    }

    private fun snapshot(ic: InputConnection) = Snapshot(
        before = ic.getTextBeforeCursor(MAX_CHARS, 0)?.toString() ?: "",
        selected = ic.getSelectedText(0)?.toString() ?: "",
        after = ic.getTextAfterCursor(MAX_CHARS, 0)?.toString() ?: "",
    )

    companion object {
        /** Per side of the cursor; plenty for keyboard-sized text and bounds the request. */
        const val MAX_CHARS = 4000
    }
}
