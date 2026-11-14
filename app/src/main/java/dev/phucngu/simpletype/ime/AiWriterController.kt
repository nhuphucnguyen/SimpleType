package dev.phucngu.simpletype.ime

import android.view.inputmethod.InputConnection
import dev.phucngu.simpletype.ai.AiException
import dev.phucngu.simpletype.ai.WriterContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drives the AI writer: the user types (or dictates) a request into [prompt] instead of the
 * field, [generate] asks the model for a draft, and the draft waits for review. Nothing touches
 * the field until [accept], which inserts the draft at the cursor (replacing any selection).
 *
 *  CLOSED → PROMPT ⇄ WORKING → REVIEW → (accept) CLOSED
 *                       ↑__________| retry / edit
 */
class AiWriterController(
    private val scope: CoroutineScope,
    private val connection: () -> InputConnection?,
    /** Settle any in-progress composition (e.g. Telex) so the field holds plain text. */
    private val prepare: (InputConnection) -> Unit,
    /** The field's text and hints, read when a draft is requested. */
    private val readContext: (InputConnection) -> WriterContext,
    private val writer: suspend (String, WriterContext) -> String,
    private val onChange: (Ui) -> Unit,
    private val onError: (AiException) -> Unit,
) {
    enum class Phase { CLOSED, PROMPT, WORKING, REVIEW }

    /** Everything the UI shows. */
    data class Ui(val phase: Phase = Phase.CLOSED, val prompt: String = "", val draft: String = "")

    val prompt = PromptBuffer()

    var ui = Ui()
        private set

    private var draft = ""
    private var job: Job? = null

    val phase: Phase get() = ui.phase

    /** Keys go to the request rather than the field. */
    val capturesKeys: Boolean get() = phase == Phase.PROMPT

    val isOpen: Boolean get() = phase != Phase.CLOSED

    fun open() {
        if (isOpen) return
        prompt.clear()
        draft = ""
        publish(Phase.PROMPT)
    }

    fun close() {
        job?.cancel()
        job = null
        prompt.clear()
        draft = ""
        publish(Phase.CLOSED)
    }

    /** Call after editing [prompt] so the UI shows the new text. */
    fun promptChanged() {
        if (phase == Phase.PROMPT) publish(Phase.PROMPT)
    }

    /** Sends the request; from REVIEW this asks for a fresh draft of the same request. */
    fun generate() {
        if (phase != Phase.PROMPT && phase != Phase.REVIEW) return
        val instruction = prompt.value.trim()
        if (instruction.isEmpty()) return
        val ic = connection() ?: return
        prepare(ic)
        val context = readContext(ic)
        publish(Phase.WORKING)
        job = scope.launch {
            val result = try {
                writer(instruction, context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                job = null
                publish(Phase.PROMPT)
                onError(e)
                return@launch
            }
            job = null
            draft = result
            publish(Phase.REVIEW)
        }
    }

    /** Stops a request in flight and goes back to editing it. */
    fun cancel() {
        if (phase != Phase.WORKING) return
        job?.cancel()
        job = null
        publish(Phase.PROMPT)
    }

    /** Back from the draft to the request, to change it. */
    fun edit() {
        if (phase != Phase.REVIEW) return
        publish(Phase.PROMPT)
    }

    /** Inserts the draft at the cursor and closes. Returns false if there was nothing to insert. */
    fun accept(): Boolean {
        if (phase != Phase.REVIEW) return false
        val ic = connection() ?: return false
        prepare(ic)
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val before = if (selected.isEmpty()) ic.getTextBeforeCursor(1, 0)?.toString().orEmpty() else ""
        ic.commitText(joinSpace(before, draft), 1) // replaces the selection, if any
        close()
        return true
    }

    private fun publish(phase: Phase) {
        ui = Ui(phase, prompt.value, draft)
        onChange(ui)
    }

    companion object {
        /** Keeps a word typed right before the cursor from running into the draft. */
        fun joinSpace(before: String, draft: String): String {
            val last = before.lastOrNull() ?: return draft
            val first = draft.firstOrNull() ?: return draft
            return if (!last.isWhitespace() && last !in "([{\"'“‘" && first.isLetterOrDigit()) " $draft" else draft
        }
    }
}
