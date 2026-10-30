package dev.phucngu.simpletype.ime

import android.view.View
import android.view.inputmethod.BaseInputConnection
import androidx.test.core.app.ApplicationProvider
import dev.phucngu.simpletype.ai.AiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiFixControllerTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Editor buffer with a cursor/selection; enough of InputConnection for the controller. */
    private class FakeIc(view: View, initial: String) : BaseInputConnection(view, false) {
        var text = initial
        var selStart = initial.length
        var selEnd = initial.length
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = text.substring(maxOf(0, selStart - n), selStart)
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = text.substring(selEnd, minOf(text.length, selEnd + n))
        override fun getSelectedText(flags: Int): CharSequence? = if (selStart == selEnd) null else text.substring(selStart, selEnd)
        override fun commitText(t: CharSequence, newCursorPosition: Int): Boolean {
            text = text.substring(0, selStart) + t + text.substring(selEnd)
            selStart += t.length
            selEnd = selStart
            return true
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            text = text.substring(0, selStart - beforeLength) + text.substring(selEnd + afterLength)
            selStart -= beforeLength
            selEnd = selStart
            return true
        }
    }

    private class Harness(val ic: FakeIc, fixer: suspend (String) -> String) {
        val states = mutableListOf<AiFixController.State>()
        val outcomes = mutableListOf<AiFixController.Outcome>()
        val sent = mutableListOf<String>()
        // Unconfined: the fixer runs inline up to its first real suspension.
        val controller = AiFixController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            connection = { ic },
            prepare = {},
            fixer = { sent += it; fixer(it) },
            onStateChange = { states += it },
            onOutcome = { outcomes += it },
        )
    }

    @Test
    fun fixes_whole_field_when_nothing_selected_then_undo_restores_it() {
        val ic = FakeIc(View(ctx), "i have too apples")
        ic.selStart = 7; ic.selEnd = 7 // cursor mid-text
        val h = Harness(ic) { "I have two apples." }

        h.controller.onButton()
        assertEquals(listOf("i have too apples"), h.sent)
        assertEquals("I have two apples.", ic.text)
        assertEquals(AiFixController.State.UNDOABLE, h.controller.state)

        h.controller.onButton()
        assertEquals("i have too apples", ic.text)
        assertEquals(AiFixController.State.IDLE, h.controller.state)
        assertEquals(AiFixController.Outcome.Undone, h.outcomes.last())
    }

    @Test
    fun fixes_only_the_selection() {
        val ic = FakeIc(View(ctx), "Keep this. fix thsi one")
        ic.selStart = 11; ic.selEnd = ic.text.length
        val h = Harness(ic) { "Fix this one." }

        h.controller.onButton()
        assertEquals(listOf("fix thsi one"), h.sent)
        assertEquals("Keep this. Fix this one.", ic.text)

        h.controller.onButton()
        assertEquals("Keep this. fix thsi one", ic.text)
    }

    @Test
    fun result_is_dropped_if_text_changed_while_waiting() {
        val ic = FakeIc(View(ctx), "helo")
        val reply = CompletableDeferred<String>()
        val h = Harness(ic) { reply.await() }

        h.controller.onButton()
        assertEquals(AiFixController.State.WORKING, h.controller.state)
        ic.commitText(" there", 1) // user keeps typing
        reply.complete("Hello")

        assertEquals("helo there", ic.text)
        assertEquals(AiFixController.Outcome.Stale, h.outcomes.last())
        assertEquals(AiFixController.State.IDLE, h.controller.state)
    }

    @Test
    fun tapping_while_working_cancels() {
        val ic = FakeIc(View(ctx), "helo")
        val h = Harness(ic) { CompletableDeferred<String>().await() }

        h.controller.onButton()
        h.controller.onButton()

        assertEquals(AiFixController.State.IDLE, h.controller.state)
        assertEquals("helo", ic.text)
    }

    @Test
    fun error_leaves_text_and_reports_failure() {
        val ic = FakeIc(View(ctx), "helo")
        val h = Harness(ic) { throw AiException(AiException.Kind.AUTH, "bad key") }

        h.controller.onButton()

        assertEquals("helo", ic.text)
        val outcome = h.outcomes.last()
        assertTrue(outcome is AiFixController.Outcome.Failed && outcome.error.kind == AiException.Kind.AUTH)
        assertEquals(AiFixController.State.IDLE, h.controller.state)
    }

    @Test
    fun empty_field_makes_no_request() {
        val h = Harness(FakeIc(View(ctx), "  ")) { "x" }
        h.controller.onButton()
        assertTrue(h.sent.isEmpty())
        assertEquals(AiFixController.Outcome.Empty, h.outcomes.last())
    }

    @Test
    fun unchanged_reply_does_not_offer_undo() {
        val h = Harness(FakeIc(View(ctx), "All good.")) { it }
        h.controller.onButton()
        assertEquals(AiFixController.Outcome.Unchanged, h.outcomes.last())
        assertEquals(AiFixController.State.IDLE, h.controller.state)
    }

    @Test
    fun typing_after_a_fix_drops_the_undo() {
        val ic = FakeIc(View(ctx), "helo")
        val h = Harness(ic) { "Hello" }
        h.controller.onButton()

        h.controller.invalidate()
        h.controller.onButton() // now starts a new fix instead of undoing

        assertEquals(2, h.sent.size)
    }
}
