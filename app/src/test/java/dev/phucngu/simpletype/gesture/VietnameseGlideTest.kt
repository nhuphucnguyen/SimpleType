package dev.phucngu.simpletype.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Vietnamese glide decoding: users swipe the base letters on qwerty (n-g-u-o-i) and the
 * decoder returns the diacritic word (người). Dictionary entries carry an explicit
 * key-sequence column that the ideal path is built from.
 */
class VietnameseGlideTest {

    private val geometry = qwertyGeometry()

    private fun qwertyGeometry(): KeyGeometry {
        val keyWidth = 100f
        val keyHeight = 120f
        val centers = HashMap<Char, GesturePoint>()
        val rows = listOf(
            "qwertyuiop" to 0f,
            "asdfghjkl" to 0.5f,
            "zxcvbnm" to 1.5f,
        )
        rows.forEachIndexed { rowIndex, (letters, offset) ->
            letters.forEachIndexed { column, c ->
                centers[c] = GesturePoint(
                    (offset + column + 0.5f) * keyWidth,
                    (rowIndex + 0.5f) * keyHeight,
                )
            }
        }
        return KeyGeometry(centers, keyWidth, keyHeight)
    }

    private fun trace(keys: String): List<GesturePoint> {
        val anchors = keys.map { geometry.centerOf(it)!! }
        val points = ArrayList<GesturePoint>()
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            for (step in 0 until 10) {
                val t = step / 10f
                points.add(GesturePoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            }
        }
        points.add(anchors.last())
        return points
    }

    // ---- Dictionary format ----

    @Test fun parses_three_column_entries_with_key_sequence() {
        val dict = GestureDictionary.parse("người\t684\tnguoi".byteInputStream())
        val entry = dict.wordsStartingWith('n').single()
        assertEquals("người", entry.word)
        assertEquals("nguoi", entry.keys)
        assertEquals(684, entry.zipf100)
    }

    @Test fun two_column_entries_use_the_word_as_keys() {
        val dict = GestureDictionary.parse("hello\t472".byteInputStream())
        val entry = dict.wordsStartingWith('h').single()
        assertEquals("hello", entry.keys)
    }

    @Test fun buckets_are_keyed_by_first_path_letter_not_word_letter() {
        // "đá" folds to keys "da": it must be findable under 'd' even though 'đ' is not a-z.
        val dict = GestureDictionary.parse("đá\t600\tda".byteInputStream())
        assertEquals("đá", dict.wordsStartingWith('d').single().word)
    }

    // ---- Decoding ----

    @Test fun swiping_base_letters_returns_the_diacritic_word() {
        val dict = GestureDictionary.parse(
            "người\t684\tnguoi\nngươi\t520\tnguoi\nnguy\t400\tnguy".byteInputStream()
        )
        val decoder = GestureDecoder(dict)
        val candidates = decoder.decode(trace("nguoi"), geometry)
        assertEquals("người", candidates.first().word)
    }

    @Test fun same_path_diacritic_variants_rank_by_frequency() {
        val dict = GestureDictionary.parse(
            "được\t670\tduoc\nduốc\t250\tduoc\ndược\t480\tduoc".byteInputStream()
        )
        val decoder = GestureDecoder(dict)
        val words = decoder.decode(trace("duoc"), geometry).map { it.word }
        assertEquals(listOf("được", "dược", "duốc"), words)
    }

    @Test fun ideal_path_uses_keys_not_the_diacritic_word() {
        // Keys "va" ends on 'a'; the word ends on 'à' which has no key. Must still decode.
        val dict = GestureDictionary.parse("và\t720\tva".byteInputStream())
        val decoder = GestureDecoder(dict)
        assertEquals("và", decoder.decode(trace("va"), geometry).first().word)
    }

    // ---- Integration with the bundled Vietnamese dictionary ----

    @Test fun bundled_vietnamese_dictionary_decodes_common_words() {
        val file = File("src/main/assets/dictionaries/vi.txt")
        assertTrue("bundled Vietnamese dictionary missing", file.exists())
        val dict = file.inputStream().use { GestureDictionary.parse(it) }
        assertTrue(dict.size > 4_000)
        val decoder = GestureDecoder(dict)

        val expectations = mapOf(
            "nguoi" to "người",
            "duoc" to "được",
            "khong" to "không",
            "viet" to "việt",
            "trong" to "trong",
        )
        for ((keys, word) in expectations) {
            val candidates = decoder.decode(trace(keys), geometry)
            assertEquals("expected '$word' when swiping '$keys'", word, candidates.first().word)
        }
    }

    @Test fun local_dialect_words_from_extras_reach_the_suggestion_strip() {
        val file = File("src/main/assets/dictionaries/vi.txt")
        assertTrue("bundled Vietnamese dictionary missing", file.exists())
        val dict = file.inputStream().use { GestureDictionary.parse(it) }
        val decoder = GestureDecoder(dict)

        // Force-included via VI_EXTRA_WORDS in tools/generate_gesture_dictionary.py.
        // ("dồ" is intentionally absent: its "do" path is owned by đó/độ/do/đồ/đô.)
        val expectations = mapOf(
            "rua" to "rứa",
            "me" to "mệ",
        )
        for ((keys, word) in expectations) {
            val words = decoder.decode(trace(keys), geometry).map { it.word }
            assertTrue("expected '$word' in the strip when swiping '$keys', got $words", word in words)
        }
    }
}
