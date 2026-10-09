package dev.phucngu.simpletype.text

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphemesTest {

    @Test fun plain_letter_is_one_char() {
        assertEquals(1, lastGraphemeLength("abc"))
    }

    @Test fun empty_text_has_nothing_to_delete() {
        assertEquals(0, lastGraphemeLength(""))
    }

    @Test fun surrogate_pair_emoji_is_deleted_whole() {
        assertEquals(2, lastGraphemeLength("hi😀"))
    }

    @Test fun skin_tone_modifier_stays_with_its_emoji() {
        assertEquals("👍🏽".length, lastGraphemeLength("ok👍🏽"))
    }

    @Test fun zwj_family_is_one_grapheme() {
        val family = "👨‍👩‍👧"
        assertEquals(family.length, lastGraphemeLength("a$family"))
    }

    @Test fun flag_is_one_grapheme() {
        assertEquals("🇻🇳".length, lastGraphemeLength("x🇻🇳"))
    }

    @Test fun combining_vietnamese_tone_mark_stays_with_its_base() {
        // "e" + combining circumflex + combining acute = ế in decomposed form.
        assertEquals(3, lastGraphemeLength("tiế"))
    }
}
