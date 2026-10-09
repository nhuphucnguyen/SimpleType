package dev.phucngu.simpletype.ime.email

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedEmailsTest {

    private fun saved(address: String, uses: Int, lastUsed: Long) = SavedEmail(address, uses, lastUsed)

    // ---- Which fields are email fields ----

    private val text = InputType.TYPE_CLASS_TEXT

    @Test fun email_input_types_are_email_fields() {
        assertTrue(SavedEmails.isEmailField(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, hint = null))
        assertTrue(SavedEmails.isEmailField(text or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, hint = null))
    }

    @Test fun plain_text_field_with_an_email_hint_counts() {
        // Many web forms use a plain text input labelled "Email".
        assertTrue(SavedEmails.isEmailField(text, hint = "Email address"))
        assertTrue(SavedEmails.isEmailField(text, hint = "Your e-mail"))
    }

    @Test fun other_fields_are_not_email_fields() {
        assertFalse(SavedEmails.isEmailField(text, hint = "Name"))
        assertFalse(SavedEmails.isEmailField(text, hint = null))
        assertFalse(SavedEmails.isEmailField(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, hint = "Email password"))
        assertFalse(SavedEmails.isEmailField(InputType.TYPE_CLASS_NUMBER, hint = "Email code"))
    }

    // ---- Recognising and extracting addresses ----

    @Test fun recognises_ordinary_addresses() {
        assertTrue(SavedEmails.isEmail("phuc@example.com"))
        assertTrue(SavedEmails.isEmail("first.last+tag@mail.co.uk"))
    }

    @Test fun rejects_partial_or_malformed_addresses() {
        assertFalse(SavedEmails.isEmail("phuc@example"))
        assertFalse(SavedEmails.isEmail("phuc@"))
        assertFalse(SavedEmails.isEmail("@example.com"))
        assertFalse(SavedEmails.isEmail("phuc example.com"))
        assertFalse(SavedEmails.isEmail("a@b@c.com"))
        assertFalse(SavedEmails.isEmail(""))
    }

    @Test fun extracts_every_address_from_a_field() {
        assertEquals(
            listOf("a@x.com", "b@y.org"),
            SavedEmails.extract(" a@x.com, b@y.org;  not-an-email "),
        )
    }

    @Test fun extracting_from_text_without_addresses_finds_nothing() {
        assertEquals(emptyList<String>(), SavedEmails.extract("hello world"))
    }

    // ---- The token being typed ----

    @Test fun current_token_is_the_text_after_the_last_separator() {
        assertEquals("ph", SavedEmails.currentToken("ph"))
        assertEquals("b", SavedEmails.currentToken("a@x.com, b"))
        assertEquals("", SavedEmails.currentToken("a@x.com; "))
        assertEquals("", SavedEmails.currentToken(""))
    }

    // ---- Learning ----

    @Test fun recording_a_new_address_saves_it_with_one_use() {
        assertEquals(listOf(saved("a@x.com", 1, 100)), SavedEmails.record(emptyList(), "a@x.com", now = 100))
    }

    @Test fun recording_again_counts_the_use_and_matches_case_insensitively() {
        val updated = SavedEmails.record(listOf(saved("Phuc@X.com", 2, 100)), "phuc@x.com", now = 200)
        assertEquals(listOf(saved("phuc@x.com", 3, 200)), updated)
    }

    @Test fun recording_trims_surrounding_whitespace() {
        assertEquals("a@x.com", SavedEmails.record(emptyList(), "  a@x.com ", now = 1).single().address)
    }

    @Test fun recording_an_invalid_address_changes_nothing() {
        val list = listOf(saved("a@x.com", 1, 100))
        assertEquals(list, SavedEmails.record(list, "not-an-email", now = 200))
    }

    @Test fun storage_is_capped_keeping_the_most_used() {
        var list = emptyList<SavedEmail>()
        list = SavedEmails.record(list, "keep@x.com", now = 1)
        list = SavedEmails.record(list, "keep@x.com", now = 2)
        for (i in 1..SavedEmails.MAX_STORED + 3) list = SavedEmails.record(list, "once$i@x.com", now = 10L + i)

        assertEquals(SavedEmails.MAX_STORED, list.size)
        assertTrue(list.any { it.address == "keep@x.com" })
    }

    @Test fun remove_forgets_an_address_regardless_of_case() {
        val list = listOf(saved("a@x.com", 1, 1), saved("b@x.com", 1, 2))
        assertEquals(listOf(saved("b@x.com", 1, 2)), SavedEmails.remove(list, "A@X.com"))
    }

    // ---- Suggesting ----

    private val mine = listOf(
        saved("work@company.com", uses = 5, lastUsed = 100),
        saved("phuc@gmail.com", uses = 9, lastUsed = 50),
        saved("phuc.side@proton.me", uses = 5, lastUsed = 300),
        saved("old@yahoo.com", uses = 1, lastUsed = 10),
    )

    @Test fun empty_field_suggests_the_top_three_by_use_then_recency() {
        assertEquals(
            listOf("phuc@gmail.com", "phuc.side@proton.me", "work@company.com"),
            SavedEmails.suggest(mine, token = ""),
        )
    }

    @Test fun typing_narrows_to_addresses_starting_with_the_prefix_ignoring_case() {
        assertEquals(listOf("phuc@gmail.com", "phuc.side@proton.me"), SavedEmails.suggest(mine, token = "PH"))
        assertEquals(listOf("phuc.side@proton.me"), SavedEmails.suggest(mine, token = "phuc."))
    }

    @Test fun a_fully_typed_address_is_not_suggested_again() {
        assertEquals(emptyList<String>(), SavedEmails.suggest(mine, token = "phuc@gmail.com"))
    }

    @Test fun nothing_saved_means_no_suggestions() {
        assertEquals(emptyList<String>(), SavedEmails.suggest(emptyList(), token = ""))
    }

    // ---- Persistence format ----

    @Test fun encode_decode_round_trips() {
        assertEquals(mine, SavedEmails.decode(SavedEmails.encode(mine)))
    }

    @Test fun decoding_skips_corrupt_lines() {
        assertEquals(
            listOf(saved("a@x.com", 2, 5)),
            SavedEmails.decode("a@x.com\t2\t5\ngarbage\nnot-an-email\t1\t1\nb@x.com\tx\t1"),
        )
        assertEquals(emptyList<SavedEmail>(), SavedEmails.decode(null))
    }
}
