package dev.phucngu.simpletype.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class LanguageAutoSwitchTest {

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

    /** Types [keys] in Vietnamese mode and ends the word: the English fix, or null. */
    private fun inVietnamese(keys: String): String? {
        val telex = TelexEngine()
        keys.forEach { telex.input(it) }
        return switcher.englishFor(telex.typedKeys, telex.rawKeys, telex.telexForm)
    }

    // ---- Vietnamese mode: English words go back to their keys ----

    @Test fun english_word_with_vietnamese_mangling_goes_back() = assertEquals("more", inVietnamese("more"))
    @Test fun english_word_with_a_tone_goes_back() = assertEquals("test", inVietnamese("test"))
    @Test fun doubled_tone_key_keeps_both_letters() = assertEquals("office", inVietnamese("office"))
    @Test fun doubled_letters_kept_in_issue() = assertEquals("issue", inVietnamese("issue"))
    @Test fun err_keeps_both_rs() = assertEquals("err", inVietnamese("err"))
    @Test fun capitalised_english_word_goes_back() = assertEquals("Please", inVietnamese("Please"))
    @Test fun rare_vietnamese_reading_loses_to_common_english() = assertEquals("has", inVietnamese("has"))

    @Test fun vietnamese_word_stays() = assertNull(inVietnamese("khoong"))
    @Test fun vietnamese_word_with_tone_in_middle_stays() = assertNull(inVietnamese("truwowngf"))
    @Test fun common_vietnamese_tie_stays() = assertNull(inVietnamese("last")) // lát
    @Test fun common_vietnamese_tie_this_stays() = assertNull(inVietnamese("this")) // thí
    @Test fun not_english_stays() = assertNull(inVietnamese("tooi"))
    @Test fun acronym_is_left_alone() = assertNull(inVietnamese("USA"))
    @Test fun unchanged_word_is_left_alone() = assertNull(inVietnamese("man"))

    @Test fun stale_keys_after_backspace_are_not_trusted() {
        val telex = TelexEngine()
        "tess".forEach { telex.input(it) } // "tes" (escaped)
        telex.backspace()
        telex.backspace()
        "o".forEach { telex.input(it) }
        val fix = switcher.englishFor(telex.typedKeys, telex.rawKeys, telex.telexForm)
        assertEquals(null, fix?.takeIf { it != telex.composing })
    }

    // ---- English mode: Vietnamese typed in Telex is converted ----

    @Test fun telex_word_converts() = assertEquals("không", switcher.vietnameseFor("khoong"))
    @Test fun tone_anywhere_converts() {
        assertEquals("trường", switcher.vietnameseFor("truowfng"))
        assertEquals("trường", switcher.vietnameseFor("truongwf"))
    }
    @Test fun capital_kept_when_converting() = assertEquals("Tôi", switcher.vietnameseFor("Tooi"))
    @Test fun much_more_common_vietnamese_tie_converts() = assertEquals("có", switcher.vietnameseFor("cos"))

    @Test fun english_word_stays() = assertNull(switcher.vietnameseFor("cheers"))
    @Test fun english_tie_stays() = assertNull(switcher.vietnameseFor("been"))
    @Test fun plain_letters_stay() = assertNull(switcher.vietnameseFor("di"))
    @Test fun non_word_telex_result_stays() = assertNull(switcher.vietnameseFor("asap"))
    @Test fun tie_after_a_clearly_english_word_stays() = assertNull(switcher.vietnameseFor("cos", previousWord = "video"))
    @Test fun tie_after_a_vietnamese_word_converts() = assertEquals("có", switcher.vietnameseFor("cos", previousWord = "anh"))
    @Test fun acronym_stays() = assertNull(switcher.vietnameseFor("DDos"))
}
