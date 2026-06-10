package dev.phucngu.simpletype.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandMatcherTest {

    private val matcher = CommandMatcher()

    @Test fun matches_english_commands() {
        assertEquals(VoiceAction.DeleteWord, matcher.match("delete that"))
        assertEquals(VoiceAction.NewLine, matcher.match("new line"))
        assertEquals(VoiceAction.Undo, matcher.match("undo"))
        assertEquals(VoiceAction.SelectAll, matcher.match("select all"))
        assertEquals(VoiceAction.StopListening, matcher.match("stop listening"))
    }

    @Test fun matches_vietnamese_commands() {
        assertEquals(VoiceAction.DeleteWord, matcher.match("xóa từ"))
        assertEquals(VoiceAction.NewLine, matcher.match("xuống dòng"))
        assertEquals(VoiceAction.Undo, matcher.match("hoàn tác"))
    }

    @Test fun normalizes_case_and_punctuation() {
        assertEquals(VoiceAction.DeleteWord, matcher.match("Delete that."))
        assertEquals(VoiceAction.StopListening, matcher.match("Stop listening!"))
    }

    @Test fun non_command_is_committed_as_text() {
        assertEquals(VoiceAction.CommitText("hello world"), matcher.match("hello world"))
    }

    @Test fun type_escape_hatch_commits_literal_words() {
        assertEquals(VoiceAction.CommitText("delete that"), matcher.match("type delete that"))
        assertEquals(VoiceAction.CommitText("new line"), matcher.match("gõ new line"))
    }

    @Test fun low_confidence_command_is_committed_as_text() {
        assertEquals(VoiceAction.CommitText("undo"), matcher.match("undo", confidence = 0.2f))
    }

    @Test fun empty_utterance_is_empty_text() {
        assertEquals(VoiceAction.CommitText(""), matcher.match("   "))
    }
}
