package dev.phucngu.simpletype.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureCommitTest {

    @Test fun adds_space_after_a_word() {
        assertEquals(" world", GestureCommit.textToCommit("world", "hello", capitalize = false))
    }

    @Test fun no_space_at_start_of_field() {
        assertEquals("hello", GestureCommit.textToCommit("hello", "", capitalize = false))
        assertEquals("hello", GestureCommit.textToCommit("hello", null, capitalize = false))
    }

    @Test fun no_space_after_whitespace() {
        assertEquals("world", GestureCommit.textToCommit("world", "hello ", capitalize = false))
        assertEquals("world", GestureCommit.textToCommit("world", "hello\n", capitalize = false))
    }

    @Test fun no_space_after_opening_punctuation() {
        assertEquals("hello", GestureCommit.textToCommit("hello", "(", capitalize = false))
        assertEquals("hello", GestureCommit.textToCommit("hello", "\"", capitalize = false))
    }

    @Test fun space_after_closing_punctuation() {
        assertEquals(" hello", GestureCommit.textToCommit("hello", "well.", capitalize = false))
        assertEquals(" hello", GestureCommit.textToCommit("hello", "yes!", capitalize = false))
    }

    @Test fun capitalizes_when_requested() {
        assertEquals("Hello", GestureCommit.textToCommit("hello", "", capitalize = true))
        assertEquals(" Hello", GestureCommit.textToCommit("hello", "ok.", capitalize = true))
    }

    @Test fun replacement_matches_previous_capitalization() {
        assertEquals("World", GestureCommit.matchCapitalization("Hello", "world"))
        assertEquals("world", GestureCommit.matchCapitalization("hello", "world"))
    }
}
