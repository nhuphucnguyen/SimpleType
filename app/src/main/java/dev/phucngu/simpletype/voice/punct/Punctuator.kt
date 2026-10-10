package dev.phucngu.simpletype.voice.punct

import android.content.res.AssetManager
import kotlin.math.exp

/**
 * Restores dots and commas in uncased, punctuation-free Vietnamese ASR text, e.g.
 * "anh ơi chiều nay anh có rảnh không em muốn nhờ anh một việc" →
 * "anh ơi, chiều nay anh có rảnh không. em muốn nhờ anh một việc."
 *
 * Mirrors `punctuate()` in the vietnamese-punctuation repo's `src/predict.py`: each word is
 * stripped of punctuation, lowercased and BPE-encoded; the model labels the first token of
 * every word; [decide] turns that row into O / COMMA / PERIOD. Casing is the caller's job.
 *
 * @param logits the model, `ids -> [ids.size][3]` (a [PunctModel] on device, a fake in tests).
 */
class Punctuator(
    private val tokenizer: BpeTokenizer,
    private val logits: (IntArray) -> Array<FloatArray>,
) {

    fun punctuate(text: String): String {
        val words = cleanWords(text)
        if (words.isEmpty()) return text
        val labels = labels(words)
        return words.indices.joinToString(" ") { words[it] + MARKS[labels[it]] }
    }

    private fun labels(words: List<String>): IntArray {
        val encoded = words.map { tokenizer.bpeWord(it.lowercase()).let { e -> if (e.size > MAX_SUB) e.copyOf(MAX_SUB) else e } }
        val labels = IntArray(words.size)
        var lastRow: FloatArray? = null
        var start = 0
        while (start < words.size) {
            // greedy non-overlapping window of whole words
            var end = start
            var n = 0
            while (end < words.size) {
                val len = encoded[end].size
                if (end > start && (n + len > MAX_SUB || end - start >= MAX_WORDS)) break
                n += len
                end++
            }
            val ids = IntArray(n)
            val firstPos = IntArray(end - start) { -1 }
            var pos = 0
            for (w in start until end) {
                val e = encoded[w]
                if (e.isEmpty()) continue // no known characters: stays O
                firstPos[w - start] = pos
                e.copyInto(ids, pos)
                pos += e.size
            }
            if (n > 0) {
                val rows = logits(ids)
                for (w in start until end) {
                    val p = firstPos[w - start]
                    if (p < 0) continue
                    labels[w] = decide(rows[p])
                    lastRow = rows[p]
                }
            }
            start = end
        }
        // The end of a segment closes the sentence: never a trailing comma, and a low
        // period bar catches completed sentences.
        val row = lastRow
        if (row != null && (labels.last() != O || probs(row)[PERIOD] > T_END_PERIOD)) {
            labels[labels.size - 1] = PERIOD
        }
        return labels
    }

    /** Break-then-mark: punctuate at all, then which mark (see thresholds.json). */
    private fun decide(row: FloatArray): Int {
        val p = probs(row)
        if (p[COMMA] + p[PERIOD] <= T_BREAK) return O
        return if (p[COMMA] * COMMA_BOOST > p[PERIOD]) COMMA else PERIOD
    }

    private fun probs(v: FloatArray): FloatArray {
        val max = maxOf(v[0], v[1], v[2])
        val e = FloatArray(3) { exp(v[it] - max) }
        val sum = e[0] + e[1] + e[2]
        return FloatArray(3) { e[it] / sum }
    }

    companion object {
        const val MAX_LEN = PunctModel.MAX_LEN
        private const val MAX_SUB = MAX_LEN - 2
        private const val MAX_WORDS = 110

        private const val O = 0
        private const val COMMA = 1
        private const val PERIOD = 2
        private val MARKS = arrayOf("", ",", ".")

        // Calibrated decision rule, from artifacts/export/thresholds.json of the model release.
        private const val T_BREAK = 0.6f
        private val COMMA_BOOST = exp(0.5f) // comma_bias
        private const val T_END_PERIOD = 0.02f

        private val LEAD_PUNCT = "([{\"'“‘«*–—".toSet()
        private val TRAIL_PUNCT = ".,!?;:…)]}\"'”’»*–—".toSet()

        /** Whitespace words with surrounding punctuation removed; pure-symbol tokens dropped. */
        fun cleanWords(text: String): List<String> = text.split(WS).mapNotNull { raw ->
            var i = 0
            var j = raw.length
            while (i < j && raw[i] in LEAD_PUNCT) i++
            while (j > i && raw[j - 1] in TRAIL_PUNCT) j--
            raw.substring(i, j).takeIf { w -> w.any { it.isLetterOrDigit() } }
        }

        private val WS = Regex("\\s+")

        /** The shipped model from `assets/punct/` (~9 MB read; call off the main thread). */
        fun fromAssets(assets: AssetManager): Punctuator {
            val tokenizer = BpeTokenizer.load(assets.open("punct/vocab.txt"), assets.open("punct/merges.txt"))
            val model = assets.open("punct/punct_vi.bin").use { PunctModel.load(it) }
            return Punctuator(tokenizer, model::logits)
        }
    }
}
