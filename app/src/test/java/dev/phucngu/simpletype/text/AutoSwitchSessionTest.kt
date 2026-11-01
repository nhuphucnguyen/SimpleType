package dev.phucngu.simpletype.text

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Look-back: a word that makes the language clear also fixes the unclear words just before it. */
class AutoSwitchSessionTest {

    private val switcher by lazy {
        fun load(path: String) = File(path).readLines().mapNotNull { line ->
            val columns = line.split('\t')
            columns.getOrNull(1)?.toIntOrNull()?.let { columns[0].lowercase() to it }
        }.toMap()
        LanguageAutoSwitch(
            load("src/main/assets/dictionaries/en.txt"),
            load("src/main/assets/dictionaries/vi.txt"),
        )
    }

    /**
     * Types [text] word by word (single spaces, no manual switching) starting in Vietnamese mode
     * when [vietnamese], and returns what ends up on screen.
     */
    private fun type(text: String, vietnamese: Boolean): String {
        val session = AutoSwitchSession(switcher)
        val shown = ArrayList<String>()
        var inVietnamese = vietnamese
        for (keys in text.split(' ')) {
            val outcome = if (inVietnamese) {
                val telex = TelexEngine()
                keys.forEach { telex.input(it) }
                session.finishVietnamese(telex.typedKeys, telex.rawKeys, telex.telexForm, telex.composing, spaceAfter = true)
            } else {
                session.finishEnglish(keys, shown.lastOrNull(), spaceAfter = true)
            }
            for (rewrite in outcome.earlier) {
                val index = shown.size - rewrite.wordsBack
                assertEquals("rewrite target", rewrite.from, shown[index])
                shown[index] = rewrite.to
            }
            shown += outcome.word
            outcome.switchToVietnamese?.let { inVietnamese = it }
        }
        return shown.joinToString(" ")
    }

    private fun inVietnamese(text: String) = type(text, vietnamese = true)
    private fun inEnglish(text: String) = type(text, vietnamese = false)

    // ---- Vietnamese mode ----

    @Test fun plain_english_word_fixes_the_tie_before_it() =
        assertEquals("this makes sense", inVietnamese("this makes sense"))

    @Test fun english_fix_flips_an_english_leaning_tie_before_it() =
        assertEquals("last week", inVietnamese("last week"))

    @Test fun common_vietnamese_word_before_english_stays() =
        assertEquals("có test", inVietnamese("cos test"))

    @Test fun vietnamese_sentence_is_untouched() =
        assertEquals("tôi thấy thí sinh", inVietnamese("tooi thaays this sinh"))

    @Test fun mixed_vietnamese_then_english_noun_stays() =
        assertEquals("mình test lại", inVietnamese("minhf test laij"))

    @Test fun look_back_reaches_two_words() =
        assertEquals("last this makes", inVietnamese("last this makes"))

    @Test fun look_back_stops_at_a_clearly_vietnamese_word() =
        assertEquals("thí tôi this makes", inVietnamese("this tooi this makes"))

    @Test fun acronym_never_flips_the_word_before_it() =
        assertEquals("bên API", inVietnamese("been API"))

    // ---- English mode ----

    @Test fun vietnamese_word_fixes_the_tie_before_it() =
        assertEquals("check mail có thể", inEnglish("check mail cos theer"))

    @Test fun english_sentence_is_untouched() =
        assertEquals("the theme of rust", inEnglish("the theme of rust"))

    @Test fun english_tie_before_vietnamese_stays_when_english_is_more_common() =
        assertEquals("been đi", inEnglish("been ddi"))

    // ---- History ----

    @Test fun punctuation_breaks_the_look_back() {
        val session = AutoSwitchSession(switcher)
        val thi = TelexEngine().apply { "this".forEach { input(it) } }
        session.finishVietnamese(thi.typedKeys, thi.rawKeys, thi.telexForm, thi.composing, spaceAfter = false)
        val makes = TelexEngine().apply { "makes".forEach { input(it) } }
        val outcome = session.finishVietnamese(makes.typedKeys, makes.rawKeys, makes.telexForm, makes.composing, spaceAfter = true)
        assertEquals(emptyList<AutoSwitchSession.Rewrite>(), outcome.earlier)
    }

    @Test fun forget_breaks_the_look_back() {
        val session = AutoSwitchSession(switcher)
        val thi = TelexEngine().apply { "this".forEach { input(it) } }
        session.finishVietnamese(thi.typedKeys, thi.rawKeys, thi.telexForm, thi.composing, spaceAfter = true)
        session.forget()
        val makes = TelexEngine().apply { "makes".forEach { input(it) } }
        val outcome = session.finishVietnamese(makes.typedKeys, makes.rawKeys, makes.telexForm, makes.composing, spaceAfter = true)
        assertEquals(emptyList<AutoSwitchSession.Rewrite>(), outcome.earlier)
    }
}
