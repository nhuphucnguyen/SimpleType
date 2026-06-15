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

    // A second w after a lone w → ư escapes to a literal w (so English words are typeable).
    @Test fun double_w_emits_literal_w() = assertEquals("w", type("ww"))
    @Test fun double_w_lets_you_type_web() = assertEquals("web", type("wweb"))
    // A real u+w horn still reverts to "uw", not "w".
    @Test fun uww_reverts_to_uw() = assertEquals("uw", type("uww"))

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

    // A horn key typed after the coda reaches back to the nucleus: "duong" + w → "dương".
    @Test fun horn_after_coda_duong() = assertEquals("dương", type("duongw"))
    @Test fun horn_after_coda_huong() = assertEquals("hương", type("huongw"))
    @Test fun horn_after_coda_lang() = assertEquals("lăng", type("langw"))
    @Test fun horn_after_coda_qu_glide() = assertEquals("quơn", type("quonw"))
    // w on a circumflex vowel swaps the mark to a breve/horn: "trân" + w → "trăn" (â→ă),
    // across the coda and with the tone preserved ("trấn" + w → "trắn").
    @Test fun horn_swaps_circumflex_to_breve() = assertEquals("trăn", type("traanw"))
    @Test fun horn_swaps_circumflex_keeps_tone() = assertEquals("trắn", type("traansw"))
    @Test fun horn_swaps_circumflex_adjacent() = assertEquals("tră", type("traaw"))

    // A 'd' typed after the coda reaches back to a 'd' onset: "dương" + d → "đương".
    @Test fun dbar_after_coda_duong() = assertEquals("đương", type("duongwd"))
    @Test fun dbar_after_coda_dong() = assertEquals("đông", type("dongod"))
    // ...but with no coda the trailing d is a literal, so English "dad" survives.
    @Test fun dbar_after_coda_keeps_dad() = assertEquals("dad", type("dad"))
    // An open syllable that already carries a diacritic is committed to Vietnamese, so a trailing
    // d bars the onset there too: "dâu" + d → "đâu", "dá" + d → "đá". Plain "dad" still survives.
    @Test fun dbar_open_syllable_dau() = assertEquals("đâu", type("dauad"))
    @Test fun dbar_open_syllable_toned() = assertEquals("đá", type("dasd"))

    // Re-typing a closed rime circumflexes the earlier vowel and absorbs the repeated coda:
    // "trong" + "ong" + tone → "trống" (the doubled o acts like oo→ô across the "ng").
    @Test fun retype_rime_circumflex_trong() = assertEquals("trống", type("trongongs"))
    @Test fun retype_rime_circumflex_cong() = assertEquals("công", type("conong"))
    // Without re-typing the coda, the lone repeated vowel still circumflexes: "trong" + "o" + "s".
    @Test fun retype_vowel_only_circumflex() = assertEquals("trống", type("trongos"))

    // Escaping a rime-retype: after "rec" + "e" → "rêc", pressing the same vowel again cancels
    // the circumflex and adds a literal vowel, so English words type through ("receeipt").
    @Test fun retype_escape_adds_literal_vowel() = assertEquals("rece", type("recee"))
    @Test fun receipt_types_through() = assertEquals("receipt", type("receeipt"))

    // Circumflex an earlier vowel across the rest of the nucleus: "dau" + "a" → "dâu".
    @Test fun circumflex_across_nucleus_dau() = assertEquals("dâu", type("daua"))
    @Test fun circumflex_across_nucleus_cau() = assertEquals("câu", type("caua"))
    // Re-pressing the vowel undoes it (so the literal types through): "daua" + "a" → "daua".
    @Test fun circumflex_across_nucleus_escapes() = assertEquals("daua", type("dauaa"))

    // ---- Auto-restore: when transforms make a non-Vietnamese syllable, show the typed letters ----

    @Test fun autorestore_benefit() = assertEquals("benefit", type("benefit"))
    @Test fun autorestore_green() = assertEquals("green", type("green"))
    // "hono"→hôn, cancel with another o→"hono", then r. The cancel must collapse the raw buffer
    // too, so the later auto-restore shows "honor", not the doubled "honoor".
    @Test fun autorestore_honor_after_retype_cancel() = assertEquals("honor", type("honoor"))
    // "yes"→ýe, a 2nd s escapes the tone→"yes"; the escape must collapse raw too, so finishing
    // "...ter" auto-restores to "yester", not the doubled "yesster".
    @Test fun autorestore_yester_after_tone_escape() = assertEquals("yester", type("yesster"))
    // "her"→hẻ (r=hỏi), a 2nd r escapes the tone→"her"; the escape must collapse raw too, so
    // finishing "...mes" auto-restores to "hermes", not the doubled "herrmes".
    @Test fun autorestore_hermes_after_tone_escape() = assertEquals("hermes", type("herrmes"))
    // "error" has a literal "rr" and r=hỏi. Typing e,r(ẻ),r(escape→er),r(→err),o,r: the final r is a
    // real coda, not an escape of the now-stale tone on 'e'. It must not cancel that tone and collapse
    // the raw buffer (that drops an r → "eror"); auto-restore should show "error".
    @Test fun autorestore_error_after_stale_tone() = assertEquals("error", type("errror"))
    // "forza": r puts a hỏi tone on o (fỏ, shown "for"), z removes it (fo). The buffer is now plain
    // ASCII and invalid, so auto-restore must still fall back to the raw keystrokes — "forza", not "fo".
    @Test fun autorestore_forza_after_tone_removed() = assertEquals("forza", type("forza"))
    // "aa"→â, a 3rd a reverts→"aa"; the revert must collapse raw, so "...rd" restores to "aard".
    @Test fun autorestore_aard_after_circumflex_revert() = assertEquals("aard", type("aaard"))
    // "raw": ă then a 2nd w reverts→"raw"; the revert must collapse raw, so "...er" gives "rawer".
    @Test fun autorestore_rawer_after_horn_revert() = assertEquals("rawer", type("rawwer"))
    // 'w'→ư and 'r'=hỏi drag "world" down the VN path; the invalid "ld" coda must restore it.
    @Test fun autorestore_world() = assertEquals("world", type("world"))
    // Escaping the lone ư with a second w (ww→w) must also collapse the raw buffer, so a later
    // auto-restore shows "world", not the doubled "wworld".
    @Test fun autorestore_world_after_w_escape() = assertEquals("world", type("wworld"))
    // Valid syllables are still shown in Vietnamese, even when the keys look English (beep → bếp).
    @Test fun autorestore_keeps_valid_vietnamese() {
        assertEquals("tiếng", type("tieesng"))
        assertEquals("bếp", type("beeps"))
    }
    // The existing English escapes still win (plain-ASCII buffers are kept, not restored to raw).
    @Test fun autorestore_keeps_escape_literals() {
        assertEquals("web", type("wweb"))
        assertEquals("receipt", type("receeipt"))
    }
    // A coda 'e' that re-types the nucleus 'e' (mb between them) must not echo a circumflex back.
    @Test fun member_stays_literal() = assertEquals("member", type("member"))

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

    @Test fun uo_single_w_horns_both() = assertEquals("hường", type("huowngf"))

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
