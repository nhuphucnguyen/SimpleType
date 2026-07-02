package dev.phucngu.simpletype.gesture

import java.io.BufferedReader
import java.io.InputStream

/**
 * Word-frequency dictionary for gesture decoding.
 *
 * Backing file format (one entry per line, 2 or 3 tab-separated columns):
 * `word<TAB>zipf100[<TAB>keys]` where `zipf100` is the Zipf frequency multiplied by 100
 * (e.g. "hello\t472"). `keys` is the lowercase a-z sequence of qwerty keys the user
 * swipes for this word; when omitted it defaults to the word itself (English). For
 * languages with diacritics the column carries the folded form (e.g. "người\t684\tnguoi"),
 * so users swipe base letters and get the diacritic word back.
 *
 * Entries are grouped by the first letter of `keys` so the decoder can prune candidates
 * cheaply; there is intentionally no trie — the decoder only ever scans first-letter
 * buckets.
 *
 * The in-memory structure is parsed from the plain-text asset at IME startup (background
 * thread, ~50-150 ms for 30k words). To add words or regenerate the assets, see
 * docs/glide-typing.md and tools/generate_gesture_dictionary.py.
 */
class GestureDictionary private constructor(
    private val byFirstKey: Array<List<Entry>>,
    val size: Int,
) {
    class Entry(val word: String, val zipf100: Int, val keys: String = word)

    /** All entries whose key sequence starts with [c] (lowercase a-z), or empty. */
    fun wordsStartingWith(c: Char): List<Entry> {
        val index = c - 'a'
        if (index < 0 || index >= 26) return emptyList()
        return byFirstKey[index]
    }

    companion object {
        fun parse(stream: InputStream): GestureDictionary {
            val buckets = Array(26) { ArrayList<Entry>() }
            var count = 0
            stream.bufferedReader().use { reader: BufferedReader ->
                reader.forEachLine { line ->
                    val columns = line.split('\t')
                    if (columns.size < 2) return@forEachLine
                    val word = columns[0]
                    val zipf = columns[1].toIntOrNull() ?: return@forEachLine
                    val keys = if (columns.size >= 3 && columns[2].isNotEmpty()) {
                        columns[2]
                    } else {
                        word
                    }
                    if (word.isEmpty() || keys.isEmpty()) return@forEachLine
                    val index = keys[0] - 'a'
                    if (index in 0..25) {
                        buckets[index].add(Entry(word, zipf, keys))
                        count++
                    }
                }
            }
            return GestureDictionary(Array(26) { buckets[it] }, count)
        }
    }
}
