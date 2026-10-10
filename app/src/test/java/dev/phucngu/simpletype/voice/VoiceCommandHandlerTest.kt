package dev.phucngu.simpletype.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [TextEditor] modelling a field with a cursor, for testing command execution. */
private class FakeTextEditor(initial: String = "") : TextEditor {
    val buffer = StringBuilder(initial)
    var cursor = initial.length
    var selectedAll = false

    override fun textBeforeCursor(maxChars: Int): CharSequence =
        buffer.substring(maxOf(0, cursor - maxChars), cursor)

    override fun textAfterCursor(maxChars: Int): CharSequence =
        buffer.substring(cursor, minOf(buffer.length, cursor + maxChars))

    override fun commitText(text: CharSequence) {
        buffer.insert(cursor, text)
        cursor += text.length
    }

    override fun deleteBeforeCursor(charCount: Int) {
        val from = maxOf(0, cursor - charCount)
        buffer.delete(from, cursor)
        cursor = from
    }

    override fun selectAll() { selectedAll = true }

    val text: String get() = buffer.toString()
}

class VoiceCommandHandlerTest {

    @Test fun delete_word_removes_last_word() {
        val ed = FakeTextEditor("hello world")
        VoiceCommandHandler(ed).handle(VoiceAction.DeleteWord, "delete that")
        assertEquals("hello ", ed.text)
    }

    @Test fun delete_word_then_undo_restores() {
        val ed = FakeTextEditor("hello world")
        val h = VoiceCommandHandler(ed)
        h.handle(VoiceAction.DeleteWord, "delete that")
        assertEquals("hello ", ed.text)
        h.handle(VoiceAction.Undo, "undo")
        assertEquals("hello world", ed.text)
    }

    @Test fun delete_sentence_removes_trailing_sentence() {
        val ed = FakeTextEditor("Hi there. Great day")
        VoiceCommandHandler(ed).handle(VoiceAction.DeleteSentence, "delete last sentence")
        assertEquals("Hi there.", ed.text)
    }

    @Test fun new_line_inserts_newline() {
        val ed = FakeTextEditor("abc")
        VoiceCommandHandler(ed).handle(VoiceAction.NewLine, "new line")
        assertEquals("abc\n", ed.text)
    }

    @Test fun new_paragraph_inserts_double_newline() {
        val ed = FakeTextEditor("abc")
        VoiceCommandHandler(ed).handle(VoiceAction.NewParagraph, "new paragraph")
        assertEquals("abc\n\n", ed.text)
    }

    @Test fun dictation_adds_space_between_words() {
        val ed = FakeTextEditor("hello")
        VoiceCommandHandler(ed).handle(VoiceAction.CommitText("world"), "world")
        assertEquals("hello world", ed.text)
    }

    @Test fun dictation_into_empty_field_has_no_leading_space() {
        val ed = FakeTextEditor("")
        VoiceCommandHandler(ed).handle(VoiceAction.CommitText("hello"), "hello")
        assertEquals("hello", ed.text)
    }

    @Test fun commit_then_undo_removes_text() {
        val ed = FakeTextEditor("hi ")
        val h = VoiceCommandHandler(ed)
        h.handle(VoiceAction.CommitText("there"), "there")
        assertEquals("hi there", ed.text)
        h.handle(VoiceAction.Undo, "undo")
        assertEquals("hi ", ed.text)
    }

    @Test fun select_all_invokes_editor() {
        val ed = FakeTextEditor("anything")
        VoiceCommandHandler(ed).handle(VoiceAction.SelectAll, "select all")
        assertTrue(ed.selectedAll)
    }

    @Test fun stop_listening_returns_signal() {
        val ed = FakeTextEditor("x")
        val result = VoiceCommandHandler(ed).handle(VoiceAction.StopListening, "stop listening")
        assertEquals(VoiceCommandHandler.Result.STOP_LISTENING, result)
    }

    @Test fun destructive_command_on_empty_field_falls_back_to_text() {
        val ed = FakeTextEditor("")
        val h = VoiceCommandHandler(ed)
        val result = h.handle(VoiceAction.DeleteWord, "delete that")
        // Nothing to delete → the literal utterance is typed instead.
        assertEquals("delete that", ed.text)
        assertEquals(VoiceCommandHandler.Result.HANDLED, result)
    }

    @Test fun can_undo_reflects_history() {
        val ed = FakeTextEditor("a b")
        val h = VoiceCommandHandler(ed)
        assertFalse(h.canUndo)
        h.handle(VoiceAction.DeleteWord, "delete that")
        assertTrue(h.canUndo)
        h.handle(VoiceAction.Undo, "undo")
        assertFalse(h.canUndo)
    }

    @Test fun end_to_end_with_matcher() {
        val ed = FakeTextEditor("xin chào")
        val h = VoiceCommandHandler(ed)
        val action = CommandMatcher().match("xóa từ") // Vietnamese "delete word"
        h.handle(action, "xóa từ")
        assertEquals("xin ", ed.text)
    }

    // ---- Joining a dictated segment onto the previous one ----

    private fun dictate(h: VoiceCommandHandler, text: String, join: SegmentJoin = SegmentJoin.NONE) =
        h.handle(VoiceAction.CommitText(text), text, join)

    @Test fun comma_join_replaces_the_previous_period() {
        val ed = FakeTextEditor()
        val h = VoiceCommandHandler(ed)
        dictate(h, "Anh ơi.")
        dictate(h, "chiều nay anh rảnh không.", SegmentJoin.COMMA)
        assertEquals("Anh ơi, chiều nay anh rảnh không.", ed.text)
    }

    @Test fun continue_join_drops_the_previous_period() {
        val ed = FakeTextEditor()
        val h = VoiceCommandHandler(ed)
        dictate(h, "Em về đến.")
        dictate(h, "nhà rồi.", SegmentJoin.CONTINUE)
        assertEquals("Em về đến nhà rồi.", ed.text)
    }

    @Test fun undo_after_a_join_restores_the_period() {
        val ed = FakeTextEditor()
        val h = VoiceCommandHandler(ed)
        dictate(h, "Anh ơi.")
        dictate(h, "chiều nay.", SegmentJoin.COMMA)
        h.handle(VoiceAction.Undo, "undo")
        assertEquals("Anh ơi.", ed.text)
    }

    @Test fun join_is_skipped_when_the_previous_text_was_typed() {
        val ed = FakeTextEditor("Typed by hand.")
        val h = VoiceCommandHandler(ed)
        dictate(h, "chiều nay.", SegmentJoin.COMMA)
        assertEquals("Typed by hand. Chiều nay.", ed.text)
    }

    @Test fun join_is_skipped_when_the_cursor_moved_away() {
        val ed = FakeTextEditor()
        val h = VoiceCommandHandler(ed)
        dictate(h, "Anh ơi.")
        ed.commitText(" x")
        dictate(h, "chiều nay.", SegmentJoin.COMMA)
        assertEquals("Anh ơi. x Chiều nay.", ed.text)
    }

    @Test fun join_is_skipped_after_a_command() {
        val ed = FakeTextEditor()
        val h = VoiceCommandHandler(ed)
        dictate(h, "Anh ơi.")
        h.handle(VoiceAction.NewLine, "xuống dòng")
        dictate(h, "chiều nay.", SegmentJoin.COMMA)
        assertEquals("Anh ơi.\nChiều nay.", ed.text)
    }

    @Test fun joins_chain_across_several_segments() {
        val ed = FakeTextEditor()
        val h = VoiceCommandHandler(ed)
        dictate(h, "Hôm qua.")
        dictate(h, "công ty có họp.", SegmentJoin.CONTINUE)
        dictate(h, "sếp nói tăng lương.", SegmentJoin.COMMA)
        assertEquals("Hôm qua công ty có họp, sếp nói tăng lương.", ed.text)
    }
}
