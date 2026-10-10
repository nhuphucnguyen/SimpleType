package dev.phucngu.simpletype.voice.punct

import java.io.InputStream

/**
 * BPE tokenizer of the Vietnamese punctuation model: a port of `src/simple_bpe.py` in the
 * vietnamese-punctuation repo, which is asserted id-identical to the training tokenizer.
 *
 * Per word: characters missing from the vocab are dropped, the rest is merged by rank.
 * There is no word-boundary marker and no special tokens are added.
 */
class BpeTokenizer(vocab: List<String>, merges: List<String>) {

    private val ids = HashMap<String, Int>(vocab.size * 2).apply {
        vocab.forEachIndexed { i, t -> put(t, i) }
    }

    private val ranks = HashMap<Pair<String, String>, Int>(merges.size * 2).apply {
        var rank = 0
        for (line in merges) {
            if (line.startsWith("#")) continue // "#version: ..." header
            val sp = line.indexOf(' ')
            if (sp > 0) put(line.substring(0, sp) to line.substring(sp + 1), rank++)
        }
    }

    val padId: Int = ids.getValue("[PAD]")
    private val unkId: Int = ids.getValue("[UNK]")

    fun bpeWord(word: String): IntArray {
        var parts = ArrayList<String>(word.length)
        for (ch in word) ch.toString().let { if (it in ids) parts.add(it) }
        while (parts.size > 1) {
            var best = Int.MAX_VALUE
            var bestAt = -1
            for (i in 0 until parts.size - 1) {
                val r = ranks[parts[i] to parts[i + 1]] ?: continue
                if (r < best) {
                    best = r
                    bestAt = i
                }
            }
            if (bestAt < 0) break
            val a = parts[bestAt]
            val b = parts[bestAt + 1]
            val merged = ArrayList<String>(parts.size)
            var i = 0
            while (i < parts.size) {
                if (i < parts.size - 1 && parts[i] == a && parts[i + 1] == b) {
                    merged.add(a + b)
                    i += 2
                } else {
                    merged.add(parts[i++])
                }
            }
            parts = merged
        }
        return IntArray(parts.size) { ids[parts[it]] ?: unkId }
    }

    companion object {
        fun load(vocab: InputStream, merges: InputStream): BpeTokenizer = BpeTokenizer(
            vocab.bufferedReader(Charsets.UTF_8).use { it.readLines() },
            merges.bufferedReader(Charsets.UTF_8).use { it.readLines() },
        )
    }
}
