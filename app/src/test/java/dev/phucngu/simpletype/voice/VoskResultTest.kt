package dev.phucngu.simpletype.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoskResultTest {

    @Test fun parses_final_text() {
        assertEquals("hello world", VoskResult.finalText("""{"text" : "hello world"}"""))
    }

    @Test fun parses_partial_text() {
        assertEquals("hello wor", VoskResult.partialText("""{"partial" : "hello wor"}"""))
    }

    @Test fun empty_text_when_field_absent() {
        assertEquals("", VoskResult.finalText("""{"partial" : "x"}"""))
        assertEquals("", VoskResult.partialText("""{"text" : "x"}"""))
    }

    @Test fun handles_empty_string_value() {
        assertEquals("", VoskResult.finalText("""{"text" : ""}"""))
    }

    @Test fun handles_unicode_vietnamese() {
        assertEquals("xin chào", VoskResult.finalText("""{"text" : "xin chào"}"""))
    }

    @Test fun handles_escaped_quote() {
        assertEquals("say \"hi\"", VoskResult.finalText("""{"text" : "say \"hi\""}"""))
    }

    @Test fun tolerates_no_space_after_colon() {
        assertEquals("ok", VoskResult.finalText("""{"text":"ok"}"""))
    }
}
