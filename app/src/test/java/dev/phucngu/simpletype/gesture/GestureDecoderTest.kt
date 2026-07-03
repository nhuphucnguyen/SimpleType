package dev.phucngu.simpletype.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Decoder tests over a synthetic qwerty geometry (keyWidth=100, keyHeight=120,
 * standard row stagger). Gestures are simulated as polylines through key centers.
 */
class GestureDecoderTest {

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

    private fun dictionary(vararg entries: Pair<String, Int>): GestureDictionary {
        val text = entries.joinToString("\n") { "${it.first}\t${it.second}" }
        return GestureDictionary.parse(text.byteInputStream())
    }

    /** Polyline through the key centers of [word], densified for realistic sampling. */
    private fun trace(word: String, jitterX: Float = 0f, jitterY: Float = 0f): List<GesturePoint> {
        val anchors = word.map { geometry.centerOf(it)!! }
        val points = ArrayList<GesturePoint>()
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            for (step in 0 until 10) {
                val t = step / 10f
                points.add(GesturePoint(
                    a.x + (b.x - a.x) * t + jitterX,
                    a.y + (b.y - a.y) * t + jitterY,
                ))
            }
        }
        points.add(GesturePoint(anchors.last().x + jitterX, anchors.last().y + jitterY))
        return points
    }

    // ---- Geometry helpers ----

    @Test fun resample_produces_requested_count_and_keeps_endpoints() {
        val path = trace("hello")
        val sampled = resample(path, 32)
        assertEquals(32, sampled.size)
        assertEquals(path.first(), sampled.first())
        assertTrue(sampled.last().distanceTo(path.last()) < 1f)
    }

    @Test fun resample_points_are_equidistant() {
        val sampled = resample(trace("word"), 32)
        val distances = (1 until sampled.size).map { sampled[it].distanceTo(sampled[it - 1]) }
        val expected = pathLength(trace("word")) / 31
        distances.forEach { assertEquals(expected, it, expected * 0.05f) }
    }

    // ---- Dictionary ----

    @Test fun dictionary_parses_and_buckets_by_first_letter() {
        val dict = dictionary("hello" to 472, "help" to 500, "world" to 480)
        assertEquals(3, dict.size)
        assertEquals(listOf("hello", "help"), dict.wordsStartingWith('h').map { it.word })
        assertEquals(emptyList<String>(), dict.wordsStartingWith('z').map { it.word })
    }

    // ---- Decoding ----

    @Test fun perfect_trace_decodes_the_word() {
        val dict = dictionary("hello" to 472, "help" to 500, "hero" to 450, "jelly" to 350)
        val decoder = GestureDecoder(dict)
        val candidates = decoder.decode(trace("helo"), geometry)
        assertEquals("hello", candidates.first().word)
    }

    @Test fun sloppy_trace_still_decodes() {
        val dict = dictionary("hello" to 472, "help" to 500, "hero" to 450)
        val decoder = GestureDecoder(dict)
        // Offset by roughly a third of a key in both axes.
        val candidates = decoder.decode(trace("helo", jitterX = 30f, jitterY = 35f), geometry)
        assertEquals("hello", candidates.first().word)
    }

    @Test fun frequency_breaks_ties_between_same_path_words() {
        // "to" and "too" collapse to the identical ideal path t->o.
        val dict = dictionary("too" to 600, "to" to 700)
        val decoder = GestureDecoder(dict)
        val candidates = decoder.decode(trace("to"), geometry)
        assertEquals("to", candidates.first().word)
        assertEquals("too", candidates[1].word)
    }

    @Test fun words_not_matching_endpoints_are_pruned() {
        val dict = dictionary("cat" to 500, "hello" to 472)
        val decoder = GestureDecoder(dict)
        val words = decoder.decode(trace("helo"), geometry).map { it.word }
        assertTrue("cat" !in words)
    }

    @Test fun tiny_movement_returns_nothing() {
        val dict = dictionary("hello" to 472)
        val decoder = GestureDecoder(dict)
        val path = listOf(GesturePoint(100f, 100f), GesturePoint(110f, 105f))
        assertTrue(decoder.decode(path, geometry).isEmpty())
    }

    @Test fun much_longer_word_than_gesture_is_pruned() {
        val dict = dictionary("was" to 650, "wonderfulness" to 200)
        val decoder = GestureDecoder(dict)
        val words = decoder.decode(trace("was"), geometry).map { it.word }
        assertEquals(listOf("was"), words)
    }

    @Test fun returns_at_most_max_results_candidates() {
        val dict = dictionary(
            "was" to 650, "war" to 550, "ware" to 400, "wad" to 300, "wax" to 350,
        )
        val decoder = GestureDecoder(dict)
        assertTrue(decoder.decode(trace("was"), geometry).size <= GestureDecoder.MAX_RESULTS)
    }

    // ---- Integration with the bundled dictionary ----

    @Test fun bundled_dictionary_decodes_common_words() {
        val file = File("src/main/assets/dictionaries/en.txt")
        assertTrue("bundled dictionary missing", file.exists())
        val dict = file.inputStream().use { GestureDictionary.parse(it) }
        assertTrue(dict.size > 20_000)
        val decoder = GestureDecoder(dict)

        for (word in listOf("hello", "world", "the", "keyboard", "people", "make")) {
            val traceWord = word.toCharArray().distinctConsecutive()
            val candidates = decoder.decode(trace(traceWord), geometry)
            assertEquals("expected '$word' for its own trace", word, candidates.first().word)
        }
    }

    private fun CharArray.distinctConsecutive(): String {
        val sb = StringBuilder()
        for (c in this) if (sb.isEmpty() || sb.last() != c) sb.append(c)
        return sb.toString()
    }
}
