package dev.phucngu.simpletype.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptBufferTest {
    private fun PromptBuffer.typeAll(s: String, vi: Boolean) = s.forEach { type(it, vi) }

    @Test
    fun telex_composes_vietnamese_words() {
        val p = PromptBuffer()
        p.typeAll("vieets thuw", vi = true)
        assertEquals("viết thư", p.value)
    }

    @Test
    fun english_types_letters_as_is() {
        val p = PromptBuffer()
        p.typeAll("reply ee", vi = false)
        assertEquals("reply ee", p.value)
    }

    @Test
    fun backspace_edits_composing_word_then_text() {
        val p = PromptBuffer()
        p.typeAll("a bas", vi = true)
        assertEquals("a bá", p.value)
        p.backspace()
        assertEquals("a b", p.value)
        p.backspace(); p.backspace()
        assertEquals("a", p.value)
    }

    @Test
    fun dictation_appends_with_a_space() {
        val p = PromptBuffer()
        p.typeAll("write", vi = false)
        p.appendWords(" an email ")
        assertEquals("write an email", p.value)
    }

    @Test
    fun delete_word_removes_previous_word() {
        val p = PromptBuffer()
        p.typeAll("say hello ", vi = false)
        p.deleteWord()
        assertEquals("say ", p.value)
    }
}
