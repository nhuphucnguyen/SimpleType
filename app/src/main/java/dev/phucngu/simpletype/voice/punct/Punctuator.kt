package dev.phucngu.simpletype.voice.punct

import android.content.res.AssetManager
import dev.phucngu.simpletype.voice.Continuation
import dev.phucngu.simpletype.voice.TextPunctuator
import kotlin.math.exp

/**
 * Restores punctuation (`,` `.` `?`) and proper-noun casing in uncased, punctuation-free
 * Vietnamese ASR text, e.g.
 * "anh tuấn ơi chiều nay anh có rảnh không em muốn nhờ anh một việc" →
 * "anh Tuấn ơi, chiều nay anh có rảnh không? em muốn nhờ anh một việc."
 *
 * Mirrors `punctuate()` in the vietnamese-punctuation repo's `src/predict.py`: each word is
 * stripped of punctuation, lowercased and BPE-encoded; the model labels the first token of
 * every word; [rule] turns that row into a mark and a casing. Words predicted lowercase keep
 * the caller's casing; capitalising sentence starts is the caller's job.
 *
 * Every VAD segment ends in a pause: its last word, and the last word of each earlier segment
 * shown as context, get a pause flag (1 at the word's first token). Models with a pause input
 * (VPN3) use it; the others ignore it.
 *
 * @param logits the model, `(ids, pause) -> [ids.size][3 | 4 | 7]` (a [PunctModel] on device, a
 *   fake in tests): punctuation logits O / COMMA / PERIOD (/ QUESTION), then lower / Cap / UPPER.
 */
class Punctuator(
    private val tokenizer: BpeTokenizer,
    private val logits: (IntArray, IntArray) -> Array<FloatArray>,
    private val rule: DecisionRule = DecisionRule.LEGACY,
) : TextPunctuator {

    /** A model without a pause input: `ids -> logits`. */
    constructor(
        tokenizer: BpeTokenizer,
        logits: (IntArray) -> Array<FloatArray>,
        rule: DecisionRule = DecisionRule.LEGACY,
    ) : this(tokenizer, { ids: IntArray, _: IntArray -> logits(ids) }, rule)

    private class Labels(val marks: IntArray, val cases: IntArray, val rows: Array<FloatArray?>)

    override fun punctuate(text: String): String {
        val words = cleanWords(text)
        if (words.isEmpty()) return text
        return render(words, labels(words, BooleanArray(words.size) { it == words.size - 1 }), 0)
    }

    /**
     * Labels the last [CONTEXT_WORDS] words before [text] ([earlier] segments, then [previous])
     * and [text] together, so a VAD pause is judged with the words on both sides of it instead
     * of always becoming a period.
     */
    override fun continueAfter(previous: String, text: String, earlier: List<String>): Continuation {
        val ctx = mutableListOf<String>()
        val ctxPause = mutableListOf<Boolean>()
        for (segment in earlier + previous) {
            val w = cleanWords(segment)
            ctx += w
            ctxPause += List(w.size) { it == w.size - 1 }
        }
        val prev = ctx.takeLast(CONTEXT_WORDS)
        val prevPause = ctxPause.takeLast(CONTEXT_WORDS)
        val words = cleanWords(text)
        if (words.isEmpty()) return Continuation('.', text)
        if (prev.isEmpty() || cleanWords(previous).isEmpty()) return Continuation('.', punctuate(text))
        val pauses = (prevPause + List(words.size) { it == words.size - 1 }).toBooleanArray()
        val labels = labels(prev + words, pauses)
        val row = labels.rows[prev.size - 1]
        val mark = if (row == null) '.' else MARKS[pauseMark(row)].firstOrNull()
        return Continuation(mark, render(words, labels, prev.size))
    }

    private fun render(words: List<String>, labels: Labels, offset: Int): String =
        words.indices.joinToString(" ") { cased(words[it], labels.cases[offset + it]) + MARKS[labels.marks[offset + it]] }

    private fun cased(word: String, case: Int): String = when (case) {
        UPPER -> word.uppercase()
        CAP -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    /** [pauses]: per word, whether a VAD pause follows it. */
    private fun labels(words: List<String>, pauses: BooleanArray): Labels {
        val encoded = words.map { tokenizer.bpeWord(it.lowercase()).let { e -> if (e.size > MAX_SUB) e.copyOf(MAX_SUB) else e } }
        val marks = IntArray(words.size)
        val cases = IntArray(words.size)
        val punctRows = arrayOfNulls<FloatArray>(words.size)
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
            val pause = IntArray(n)
            val firstPos = IntArray(end - start) { -1 }
            var pos = 0
            for (w in start until end) {
                val e = encoded[w]
                if (e.isEmpty()) continue // no known characters: stays O
                firstPos[w - start] = pos
                if (pauses[w]) pause[pos] = 1
                e.copyInto(ids, pos)
                pos += e.size
            }
            if (n > 0) {
                val rows = logits(ids, pause)
                for (w in start until end) {
                    val p = firstPos[w - start]
                    if (p < 0) continue
                    marks[w] = decide(rows[p])
                    cases[w] = decideCase(rows[p])
                    lastRow = rows[p]
                    punctRows[w] = rows[p]
                }
            }
            start = end
        }
        // The end of a segment closes the sentence: never a trailing comma, and a low
        // period bar catches completed sentences.
        val row = lastRow
        if (row != null) marks[marks.size - 1] = endMark(row, marks.last())
        return Labels(marks, cases, punctRows)
    }

    /** Break-then-mark: punctuate at all, then which mark (see [DecisionRule]). */
    private fun decide(row: FloatArray): Int {
        val p = punctProbs(row)
        if (p[COMMA] + p[PERIOD] + p[QUESTION] <= rule.tBreak) return O
        val comma = p[COMMA] * exp(rule.commaBias)
        val question = p[QUESTION] * exp(rule.questionBias)
        return when {
            comma >= p[PERIOD] && comma >= question -> COMMA
            p[PERIOD] >= question -> PERIOD
            else -> QUESTION
        }
    }

    /**
     * The mark at a VAD pause. The model never hears the pause, and with only the next
     * segment as right context it often prefers a comma or no mark where the speaker ended a
     * sentence, so a pause leans to a period: no mark only when the model is sure the sentence
     * goes on ("em về đến… nhà rồi"), a comma only when it clearly beats the period ("Anh ơi,").
     */
    private fun pauseMark(row: FloatArray): Int {
        val p = punctProbs(row)
        if (p[O] > PAUSE_JOIN) return O
        if (p[COMMA] * exp(PAUSE_COMMA_BIAS) > p[PERIOD] + p[QUESTION]) return COMMA
        return if (p[QUESTION] * exp(rule.questionBias) > p[PERIOD]) QUESTION else PERIOD
    }

    private fun endMark(row: FloatArray, mark: Int): Int {
        val p = punctProbs(row)
        if (mark == O && p[PERIOD] + p[QUESTION] <= rule.tEndPeriod) return O
        return if (p[QUESTION] * exp(rule.questionBias) > p[PERIOD]) QUESTION else PERIOD
    }

    private fun decideCase(row: FloatArray): Int {
        if (row.size < PUNCT_CLASSES + CASE_CLASSES) return LOWER
        val p = softmax(row, PUNCT_CLASSES, CASE_CLASSES)
        return when {
            p[UPPER] > rule.tUpper -> UPPER
            p[CAP] > rule.tCap -> CAP
            else -> LOWER
        }
    }

    /** Punctuation probabilities over O / COMMA / PERIOD / QUESTION (0 for a 3-class model). */
    private fun punctProbs(row: FloatArray): FloatArray {
        val n = minOf(row.size, PUNCT_CLASSES)
        return softmax(row, 0, n).copyOf(PUNCT_CLASSES)
    }

    private fun softmax(v: FloatArray, from: Int, n: Int): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (i in from until from + n) if (v[i] > max) max = v[i]
        val e = FloatArray(n) { exp(v[from + it] - max) }
        val sum = e.sum()
        return FloatArray(n) { e[it] / sum }
    }

    companion object {
        const val MAX_LEN = PunctModel.MAX_LEN
        private const val MAX_SUB = MAX_LEN - 2
        private const val MAX_WORDS = 110
        /** Words of the earlier segments shown to the model as left context. */
        const val CONTEXT_WORDS = 40
        /** [pauseMark]: tuned on simulated pauses in news and dictation text (m11). */
        private const val PAUSE_JOIN = 0.95f
        private const val PAUSE_COMMA_BIAS = -1.0f

        private const val O = 0
        private const val COMMA = 1
        private const val PERIOD = 2
        private const val QUESTION = 3
        private const val PUNCT_CLASSES = 4
        private val MARKS = arrayOf("", ",", ".", "?")

        private const val LOWER = 0
        private const val CAP = 1
        private const val UPPER = 2
        private const val CASE_CLASSES = 3

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
            return Punctuator(tokenizer, { ids: IntArray, pause: IntArray -> model.logits(ids, pause) },
                model.rule ?: DecisionRule.LEGACY)
        }
    }
}
