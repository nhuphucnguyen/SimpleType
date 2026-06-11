package dev.phucngu.simpletype.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelexEngineTest {

    private fun type(keys: String): String {
        val e = TelexEngine()
        keys.forEach { e.input(it) }
        return e.composing
    }

    // ---- Vowel modifiers ----

    @Test fun circumflex_a() = assertEquals("â", type("aa"))
    @Test fun circumflex_e() = assertEquals("ê", type("ee"))
    @Test fun circumflex_o() = assertEquals("ô", type("oo"))
    @Test fun dd_to_dbar() = assertEquals("đ", type("dd"))
    @Test fun horn_aw() = assertEquals("ă", type("aw"))
    @Test fun horn_ow() = assertEquals("ơ", type("ow"))
    @Test fun horn_uw() = assertEquals("ư", type("uw"))
    @Test fun lone_w_is_u_horn() = assertEquals("ư", type("w"))

    // ---- Tones on single vowels ----

    @Test fun tone_sac() = assertEquals("á", type("as"))
    @Test fun tone_huyen() = assertEquals("à", type("af"))
    @Test fun tone_hoi() = assertEquals("ả", type("ar"))
    @Test fun tone_nga() = assertEquals("ã", type("ax"))
    @Test fun tone_nang() = assertEquals("ạ", type("aj"))

    // ---- Escape behaviour: repeating a key reverts it ----

    @Test fun double_tone_emits_literal() = assertEquals("as", type("ass"))
    @Test fun triple_a_reverts_circumflex() = assertEquals("aa", type("aaa"))
    @Test fun z_removes_tone() = assertEquals("a", type("asz"))

    // ---- Whole words ----

    @Test fun tieng_viet() {
        assertEquals("tiếng", type("tieesng"))
        assertEquals("việt", type("vieejt"))
    }

    @Test fun xin_chao() {
        assertEquals("xin", type("xin"))
        assertEquals("chào", type("chaof"))
    }

    @Test fun day_with_dd() = assertEquals("đây", type("ddaay"))

    @Test fun phuong_horns() = assertEquals("phương", type("phuwowng"))

    @Test fun duong_with_tone() = assertEquals("đường", type("dduwowngf"))

    @Test fun qu_glide_skips_u() = assertEquals("quá", type("quas"))

    @Test fun gi_glide() = assertEquals("giàu", type("giauf"))

    @Test fun tone_on_marked_vowel_wins() = assertEquals("tuổi", type("tuooir"))

    @Test fun final_consonant_takes_last_vowel() = assertEquals("nước", type("nuwowcs"))

    // ---- Orthographic tone placement (ported from xkey / Unikey rules) ----

    @Test fun triple_vowel_takes_middle() = assertEquals("ngoài", type("ngoaif"))

    @Test fun oai_with_sac() = assertEquals("hoáy", type("hoays"))

    @Test fun uoi_horn_takes_o_horn() = assertEquals("người", type("nguwowif"))

    @Test fun ruou_horn_triple() = assertEquals("rượu", type("ruwowuj"))

    @Test fun modern_uy_tones_y() = assertEquals("thuý", type("thuys"))

    @Test fun old_uy_tones_u() =
        assertEquals("thúy", TelexEngine(modernStyle = false).let { e -> "thuys".forEach { e.input(it) }; e.composing })

    @Test fun oa_open_tones_o() = assertEquals("hóa", type("hoas"))

    @Test fun oa_closed_tones_a() = assertEquals("khoản", type("khoanr"))

    @Test fun ua_open_tones_u() = assertEquals("của", type("cuar"))

    @Test fun mia_tones_i() = assertEquals("mía", type("mias"))

    // ---- Tone re-positioning as the syllable grows ----

    @Test fun tone_migrates_when_final_consonant_added() = assertEquals("hoàn", type("hoafn"))

    @Test fun tone_migrates_when_vowel_added() = assertEquals("táo", type("taso"))

    // ---- Casing ----

    @Test fun uppercase_circumflex_preserved() = assertEquals("Â", type("Aa"))

    @Test fun capitalized_word() = assertEquals("Việt", type("Vieejt"))

    // ---- Backspace ----

    @Test fun backspace_removes_display_char() {
        val e = TelexEngine()
        "vieejt".forEach { e.input(it) }
        assertEquals("việt", e.composing)
        assertTrue(e.backspace())
        assertEquals("việ", e.composing)
    }

    @Test fun backspace_on_empty_returns_false() {
        val e = TelexEngine()
        assertFalse(e.backspace())
    }

    @Test fun non_letter_is_not_consumed() {
        val e = TelexEngine()
        assertFalse(e.input(' '))
        assertFalse(e.input('1'))
    }
}
