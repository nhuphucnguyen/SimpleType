package dev.phucngu.simpletype.ime

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionFieldsTest {

    // inputType values Chrome reports, read from a real page on the emulator.
    private val webInput = 0xc0a1 // <input>
    private val webTextarea = 0x2c0a1 // <textarea>
    private val webInputAutocorrectOff = 0x840a1 // <input autocomplete=off autocorrect=off>

    @Test fun web_fields_show_suggestions() {
        assertTrue(SuggestionFields.showsSuggestions(webInput))
        assertTrue(SuggestionFields.showsSuggestions(webTextarea))
    }

    /** Like Gboard: "no suggestions" doesn't hide predictions, many sites set it on chat boxes. */
    @Test fun no_suggestions_flag_still_shows_suggestions() =
        assertTrue(SuggestionFields.showsSuggestions(webInputAutocorrectOff))

    @Test fun no_suggestions_flag_stops_learning() {
        assertFalse(SuggestionFields.learnsWords(webInputAutocorrectOff))
        assertTrue(SuggestionFields.learnsWords(webInput))
    }

    /** The address bar doubles as a search box, so it gets suggestions, but typed URLs aren't learned. */
    @Test fun web_address_field_shows_suggestions_without_learning() {
        val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        assertTrue(SuggestionFields.showsSuggestions(uri))
        assertFalse(SuggestionFields.learnsWords(uri))
    }

    @Test fun emails_passwords_and_numbers_show_nothing() {
        val text = InputType.TYPE_CLASS_TEXT
        assertFalse(SuggestionFields.showsSuggestions(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(SuggestionFields.showsSuggestions(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertFalse(SuggestionFields.showsSuggestions(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertFalse(SuggestionFields.showsSuggestions(InputType.TYPE_CLASS_NUMBER))
    }
}
