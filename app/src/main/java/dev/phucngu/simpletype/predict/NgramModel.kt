package dev.phucngu.simpletype.predict

import java.io.InputStream
import java.text.Normalizer

/**
 * Pruned word trigram model for next-word prediction, parsed from
 * `assets/predict/{lang}.txt` (format in tools/generate_prediction_model.py).
 *
 * Words are addressed by id; id [START] is the sentence-start marker. N-gram lists keep only
 * the most likely successors of each context, best first, with costs of
 * `-100 * log10(P(word | context))`.
 */
class NgramModel private constructor(
    /** Display form of each word ("I", "Hà", "the"). */
    private val display: Array<String>,
    private val unigramCost: ShortArray,
    private val idByKey: HashMap<String, Int>,
    /** Word ids sorted by [foldKey], for prefix search by binary search. */
    private val byFolded: IntArray,
    private val folded: Array<String>,
    private val bigrams: HashMap<Int, Successors>,
    private val trigrams: HashMap<Long, Successors>,
) {
    class Successors(val ids: IntArray, val costs: ShortArray)

    val size: Int get() = display.size - 1

    fun word(id: Int): String = display[id]

    /** Log10 unigram probability of word [id]. */
    fun unigramLogProb(id: Int): Double = -unigramCost[id] / 100.0

    fun idOf(word: String): Int? = idByKey[word.lowercase()]

    fun bigram(ctx: Int): Successors? = bigrams[ctx]

    fun trigram(ctx2: Int, ctx1: Int): Successors? = trigrams[pairKey(ctx2, ctx1)]

    /** Calls [action] with the id of every word whose folded form starts with [foldedPrefix]. */
    fun forEachWithPrefix(foldedPrefix: String, action: (Int) -> Unit) {
        var i = lowerBound(foldedPrefix)
        while (i < byFolded.size && folded[byFolded[i]].startsWith(foldedPrefix)) {
            action(byFolded[i])
            i++
        }
    }

    /** Most frequent words overall, for when the context says nothing. */
    val topWords: IntArray by lazy {
        (1 until display.size).sortedBy { unigramCost[it] }.take(TOP_WORDS).toIntArray()
    }

    private fun lowerBound(key: String): Int {
        var lo = 0
        var hi = byFolded.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (folded[byFolded[mid]] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        const val START = 0
        private const val TOP_WORDS = 16

        private fun pairKey(a: Int, b: Int) = (a.toLong() shl 32) or b.toLong()

        /** Lowercase with diacritics and đ removed, so "viet" finds "việt" and "viết". */
        fun foldKey(word: String): String {
            val lower = word.lowercase()
            if (lower.all { it < '\u0080' }) return lower
            val nfd = Normalizer.normalize(lower, Normalizer.Form.NFD)
            val sb = StringBuilder(nfd.length)
            for (c in nfd) {
                when {
                    Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> {}
                    c == 'đ' -> sb.append('d')
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        fun parse(stream: InputStream): NgramModel {
            val display = ArrayList<String>()
            val costs = ArrayList<Short>()
            val bigrams = HashMap<Int, Successors>()
            val trigrams = HashMap<Long, Successors>()
            stream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val cols = line.split('\t')
                    when (cols[0]) {
                        "u" -> if (cols.size >= 3) {
                            display.add(cols[1])
                            costs.add(cols[2].toShortOrNull() ?: Short.MAX_VALUE)
                        }
                        "b" -> if (cols.size >= 3) {
                            val ctx = cols[1].toIntOrNull() ?: continue
                            parseSuccessors(cols[2])?.let { bigrams[ctx] = it }
                        }
                        "t" -> if (cols.size >= 4) {
                            val c2 = cols[1].toIntOrNull() ?: continue
                            val c1 = cols[2].toIntOrNull() ?: continue
                            parseSuccessors(cols[3])?.let { trigrams[pairKey(c2, c1)] = it }
                        }
                    }
                }
            }
            if (display.isEmpty()) display.add("<s>").also { costs.add(0) }
            val idByKey = HashMap<String, Int>(display.size * 2)
            val folded = Array(display.size) { "" }
            for (id in 1 until display.size) {
                idByKey.putIfAbsent(display[id].lowercase(), id)
                folded[id] = foldKey(display[id])
            }
            val byFolded = (1 until display.size).sortedBy { folded[it] }.toIntArray()
            return NgramModel(
                display.toTypedArray(),
                ShortArray(costs.size) { costs[it] },
                idByKey,
                byFolded,
                folded,
                bigrams,
                trigrams,
            )
        }

        private fun parseSuccessors(field: String): Successors? {
            val parts = field.split(' ').filter { it.isNotEmpty() }
            val ids = IntArray(parts.size)
            val costs = ShortArray(parts.size)
            for ((i, p) in parts.withIndex()) {
                val colon = p.indexOf(':')
                if (colon <= 0) return null
                ids[i] = p.substring(0, colon).toIntOrNull() ?: return null
                costs[i] = p.substring(colon + 1).toShortOrNull() ?: return null
            }
            return if (ids.isEmpty()) null else Successors(ids, costs)
        }
    }
}
