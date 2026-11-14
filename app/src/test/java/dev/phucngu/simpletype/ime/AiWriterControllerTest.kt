package dev.phucngu.simpletype.ime

import android.view.View
import android.view.inputmethod.BaseInputConnection
import androidx.test.core.app.ApplicationProvider
import dev.phucngu.simpletype.ai.AiException
import dev.phucngu.simpletype.ai.WriterContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiWriterControllerTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

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
    }

    private inner class Harness(initial: String, val writer: suspend (String, WriterContext) -> String) {
        val ic = FakeIc(View(ctx), initial)
        val errors = mutableListOf<AiException>()
        val requests = mutableListOf<Pair<String, WriterContext>>()
        val controller = AiWriterController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            connection = { ic },
            prepare = {},
            readContext = { WriterContext(before = it.getTextBeforeCursor(100, 0).toString()) },
            writer = { p, c -> requests += p to c; writer(p, c) },
            onChange = {},
            onError = { errors += it },
        )

        fun typePrompt(s: String) {
            s.forEach { controller.prompt.type(it, vietnamese = false) }
            controller.promptChanged()
        }
    }

    @Test
    fun field_is_untouched_until_the_draft_is_accepted() {
        val h = Harness("Hi Bob,") { _, _ -> "See you at 5." }
        h.controller.open()
        h.typePrompt("say see you at 5")
        h.controller.generate()
        assertEquals(AiWriterController.Phase.REVIEW, h.controller.phase)
        assertEquals("See you at 5.", h.controller.ui.draft)
        assertEquals("Hi Bob,", h.ic.text)
        assertEquals("say see you at 5", h.requests.single().first)
        assertEquals("Hi Bob,", h.requests.single().second.before)

        assertTrue(h.controller.accept())
        assertEquals("Hi Bob, See you at 5.", h.ic.text)
        assertEquals(AiWriterController.Phase.CLOSED, h.controller.phase)
    }

    @Test
    fun accept_replaces_selection() {
        val h = Harness("Hello gimme it") { _, _ -> "could you send it?" }
        h.ic.selStart = 6
        h.controller.open()
        h.typePrompt("formal")
        h.controller.generate()
        h.controller.accept()
        assertEquals("Hello could you send it?", h.ic.text)
    }

    @Test
    fun blank_prompt_does_not_call_the_model() {
        val h = Harness("") { _, _ -> "x" }
        h.controller.open()
        h.typePrompt("   ")
        h.controller.generate()
        assertTrue(h.requests.isEmpty())
        assertEquals(AiWriterController.Phase.PROMPT, h.controller.phase)
    }

    @Test
    fun failure_returns_to_prompt_keeping_the_request() {
        val h = Harness("") { _, _ -> throw AiException(AiException.Kind.NETWORK, "down") }
        h.controller.open()
        h.typePrompt("hi")
        h.controller.generate()
        assertEquals(AiWriterController.Phase.PROMPT, h.controller.phase)
        assertEquals("hi", h.controller.ui.prompt)
        assertEquals(AiException.Kind.NETWORK, h.errors.single().kind)
    }

    @Test
    fun cancel_while_working_goes_back_to_prompt_and_drops_the_result() {
        val reply = CompletableDeferred<String>()
        val h = Harness("") { _, _ -> reply.await() }
        h.controller.open()
        h.typePrompt("hi")
        h.controller.generate()
        assertEquals(AiWriterController.Phase.WORKING, h.controller.phase)
        h.controller.cancel()
        reply.complete("late")
        assertEquals(AiWriterController.Phase.PROMPT, h.controller.phase)
        assertFalse(h.controller.accept())
        assertEquals("", h.ic.text)
    }

    @Test
    fun retry_from_review_asks_again_and_edit_returns_to_prompt() {
        var n = 0
        val h = Harness("") { _, _ -> "draft ${++n}" }
        h.controller.open()
        h.typePrompt("hi")
        h.controller.generate()
        h.controller.generate()
        assertEquals("draft 2", h.controller.ui.draft)
        h.controller.edit()
        assertEquals(AiWriterController.Phase.PROMPT, h.controller.phase)
        assertTrue(h.controller.capturesKeys)
    }

    @Test
    fun join_space_only_between_words() {
        assertEquals(" Hi", AiWriterController.joinSpace("x", "Hi"))
        assertEquals("Hi", AiWriterController.joinSpace(" ", "Hi"))
        assertEquals("Hi", AiWriterController.joinSpace("", "Hi"))
        assertEquals("Hi", AiWriterController.joinSpace("(", "Hi"))
        assertEquals(", ok", AiWriterController.joinSpace("x", ", ok"))
    }
}
