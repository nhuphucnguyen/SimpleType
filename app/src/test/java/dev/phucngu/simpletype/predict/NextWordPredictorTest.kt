package dev.phucngu.simpletype.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NextWordPredictorTest {

    /** ids: 0 <s>, 1 I, 2 you, 3 see, 4 later, 5 again, 6 soon, 7 the, 8 thank, 9 việt, 10 viết, 11 nam */
    private val tiny = NgramModel.parse(
        """
        #simpletype-ngram 1 en
        u	<s>	0
        u	I	150
        u	you	140
        u	see	250
        u	later	330
        u	again	320
        u	soon	340
        u	the	145
        u	thank	300
        u	việt	350
        u	viết	330
        u	nam	300
        b	0	1:60 2:80
        b	3	2:40 7:60
        b	2	5:110 4:120
        t	3	2	4:60 5:70 6:80
        b	9	11:10
        """.trimIndent().byteInputStream(),
    )

    private fun ctx(text: String, complete: Boolean = true) = TypingContext.parse(text, complete)!!

    private fun predict(text: String, history: UserHistory = UserHistory()) =
        NextWordPredictor(tiny, history).suggest(ctx(text))

    // ---- TypingContext ----

    @Test
    fun context_after_space_has_no_partial_and_two_previous_words() {
        assertEquals(TypingContext(listOf("see", "you"), "", false), ctx("ok, I see you ", complete = false))
    }

    @Test
    fun context_marks_sentence_start_after_terminal_punctuation_or_field_start() {
        assertEquals(TypingContext(emptyList(), "", true), ctx("Done. "))
        assertEquals(TypingContext(listOf("Hi"), "", true), ctx("Hi "))
        assertEquals(TypingContext(listOf("Hi"), "", false), ctx("Hi ", complete = false))
        assertEquals(TypingContext(emptyList(), "", true), ctx(""))
    }

    @Test
    fun context_reads_word_in_progress_including_apostrophes() {
        assertEquals(TypingContext(listOf("I"), "don'", true), ctx("I don'"))
        assertEquals(TypingContext(listOf("thank"), "yo", true), ctx("ok\nthank yo", complete = false))
    }

    @Test
    fun context_breaks_at_numbers_and_symbols() {
        assertEquals(TypingContext(emptyList(), "", false), ctx("call at 5 "))
        assertNull(TypingContext.parse("mail me @han", true))
        assertNull(TypingContext.parse("room 12b", true))
    }

    // ---- Predictions ----

    @Test
    fun trigram_context_wins_over_bigram() {
        assertEquals(listOf("later", "again", "soon"), predict("See you "))
    }

    @Test
    fun bigram_used_when_no_trigram() {
        assertEquals(listOf("you", "the"), predict("I'd see ").take(2))
    }

    @Test
    fun sentence_start_predictions() {
        assertEquals(listOf("I", "you"), predict("Great! ").take(2))
    }

    @Test
    fun unknown_context_falls_back_to_common_words() {
        assertEquals(listOf("you", "the", "I"), predict("zzz "))
    }

    @Test
    fun completes_the_word_being_typed_and_keeps_the_typed_text_second() {
        assertEquals(listOf("later", "la"), predict("see you la"))
        assertEquals(listOf("see"), predict("I se").take(1))
    }

    @Test
    fun vietnamese_completion_ignores_missing_accents_but_respects_typed_ones() {
        // "viet" finds both; the more frequent "viết" leads.
        assertEquals(listOf("viết", "viet", "việt"), predict("viet"))
        // An accent already typed ("ệ") pushes the matching word first.
        assertEquals("việt", predict("việ")[0])
    }

    @Test
    fun learned_phrases_are_suggested_once_typed_twice() {
        val history = UserHistory()
        val p = NextWordPredictor(tiny, history)
        p.learn(ctx("see "), "Phúc")
        assertTrue("Phúc" !in p.suggest(ctx("see ")))
        p.learn(ctx("see "), "Phúc")
        assertEquals("Phúc", p.suggest(ctx("see "))[0])
        // Learned words complete too.
        assertEquals("Phúc", p.suggest(ctx("hello ph"))[0])
    }

    @Test
    fun sentence_initial_capital_is_not_learned_as_the_word_casing() {
        val history = UserHistory()
        val p = NextWordPredictor(tiny, history)
        p.learn(ctx(""), "Banana")
        p.learn(ctx(""), "Banana")
        assertEquals("banana", history.word("banana")!!.display)
        p.learn(ctx("I see "), "Paris")
        assertEquals("Paris", history.word("paris")!!.display)
    }

    @Test
    fun history_round_trips() {
        val history = UserHistory()
        val p = NextWordPredictor(tiny, history)
        repeat(2) { p.learn(ctx("see "), "Phúc") }
        val restored = UserHistory.parse(history.serialize())
        assertEquals("Phúc", NextWordPredictor(tiny, restored).suggest(ctx("see "))[0])
    }

    @Test
    fun casing_follows_shift_or_typed_prefix() {
        assertEquals("You", NextWordPredictor.applyCase("you", "", capitalize = true, allCaps = false))
        assertEquals("YOU", NextWordPredictor.applyCase("you", "", capitalize = false, allCaps = true))
        assertEquals("Later", NextWordPredictor.applyCase("later", "La", capitalize = false, allCaps = false))
        assertEquals("LATER", NextWordPredictor.applyCase("later", "LA", capitalize = false, allCaps = false))
        assertEquals("I", NextWordPredictor.applyCase("I", "", capitalize = false, allCaps = false))
    }

    // ---- Bundled models ----

    @Test
    fun bundled_english_model_predicts_common_phrases() {
        val model = File("src/main/assets/predict/en.txt").inputStream().use { NgramModel.parse(it) }
        val p = NextWordPredictor(model, UserHistory())
        assertTrue(model.size > 20_000)
        assertEquals("you", p.suggest(ctx("How are "))[0])
        assertTrue("you" in p.suggest(ctx("Thank ")))
        assertTrue("I'm" in p.suggest(ctx("I")) || "I" in p.suggest(ctx("I")))
        assertEquals("tomorrow", p.suggest(ctx("see you tomo"))[0])
    }

    @Test
    fun bundled_vietnamese_model_predicts_common_phrases() {
        val model = File("src/main/assets/predict/vi.txt").inputStream().use { NgramModel.parse(it) }
        val p = NextWordPredictor(model, UserHistory())
        assertTrue("Nam" in p.suggest(ctx("Việt ")) || "nam" in p.suggest(ctx("Việt ")))
        assertTrue("ơn" in p.suggest(ctx("cảm ")))
        assertTrue("không" in p.suggest(ctx("có khon")))
    }
}
