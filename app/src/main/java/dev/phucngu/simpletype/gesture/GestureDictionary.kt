package dev.phucngu.simpletype.gesture

import java.io.BufferedReader
import java.io.InputStream

/**
 * Word-frequency dictionary for gesture decoding.
 *
 * Backing file format (one entry per line): `word<TAB>zipf100` where `zipf100` is the
 * Zipf frequency multiplied by 100 (e.g. "hello\t472"). Words are lowercase a-z.
 * Entries are grouped by first letter so the decoder can prune candidates cheaply;
 * there is intentionally no trie — the decoder only ever scans first-letter buckets.
 *
 * The in-memory structure is parsed from the plain-text asset at IME startup (background
 * thread, ~50-150 ms for 30k words). To add words or regenerate the asset, see
 * docs/glide-typing.md and tools/generate_gesture_dictionary.py.
 */
class GestureDictionary private constructor(
    private val byFirstLetter: Array<List<Entry>>,
    val size: Int,
) {
    class Entry(val word: String, val zipf100: Int)

    /** All entries whose word starts with [c] (lowercase a-z), or empty. */
    fun wordsStartingWith(c: Char): List<Entry> {
        val index = c - 'a'
        if (index < 0 || index >= 26) return emptyList()
        return byFirstLetter[index]
    }

    companion object {
        fun parse(stream: InputStream): GestureDictionary {
            val buckets = Array(26) { ArrayList<Entry>() }
            var count = 0
            stream.bufferedReader().use { reader: BufferedReader ->
                reader.forEachLine { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEachLine
                    val word = line.substring(0, tab)
                    val zipf = line.substring(tab + 1).toIntOrNull() ?: return@forEachLine
                    val index = word[0] - 'a'
                    if (index in 0..25) {
                        buckets[index].add(Entry(word, zipf))
                        count++
                    }
                }
            }
            return GestureDictionary(Array(26) { buckets[it] }, count)
        }
    }
}
