package dev.phucngu.simpletype.predict

import kotlin.math.log10

/**
 * Gboard-style suggestions for the suggestion strip: the likely next word after a space,
 * and completions of the word being typed. Scores the bundled trigram [model] with stupid
 * backoff (in log10) and mixes in what the user has typed before ([history]).
 */
class NextWordPredictor(
    private val model: NgramModel?,
    private val history: UserHistory,
) {
    private class Candidate(var display: String, var score: Double)

    /**
     * Up to [limit] suggestions for [ctx], best first. When a word is being typed and the
     * exact typed text is not among the top suggestions, it is put second so the strip can
     * show it on the left, as Gboard does, for keeping a word the model doesn't know.
     */
    fun suggest(ctx: TypingContext, limit: Int = 3): List<String> {
        val prefix = ctx.partial
        val foldedPrefix = NgramModel.foldKey(prefix)
        val lowerPrefix = prefix.lowercase()
        val candidates = HashMap<String, Candidate>()

        fun offer(display: String, score: Double) {
            if (prefix.isNotEmpty()) {
                if (!NgramModel.foldKey(display).startsWith(foldedPrefix)) return
                // Typed accents ("viê") should outrank words with other accents ("viền" vs "viết").
                if (!display.lowercase().startsWith(lowerPrefix)) return offerScored(display, score - ACCENT_MISMATCH, candidates)
            }
            offerScored(display, score, candidates)
        }

        model?.let { m ->
            val (c2, c1) = modelContext(m, ctx)
            if (c2 != null && c1 != null) {
                m.trigram(c2, c1)?.let { s -> for (i in s.ids.indices) offer(m.word(s.ids[i]), -s.costs[i] / 100.0) }
            }
            if (c1 != null) {
                m.bigram(c1)?.let { s -> for (i in s.ids.indices) offer(m.word(s.ids[i]), BACKOFF - s.costs[i] / 100.0) }
            }
            if (prefix.isNotEmpty()) {
                m.forEachWithPrefix(foldedPrefix) { id -> offer(m.word(id), 2 * BACKOFF + m.unigramLogProb(id)) }
            }
        }

        val followers = history.followers(historyContext(ctx) ?: "")
        val total = followers.values.sumOf { it.count }
        for (e in followers.values) {
            if (e.count >= MIN_USER_COUNT) offer(e.display, log10(e.count / (total + 1.0)))
        }
        if (prefix.isNotEmpty()) {
            for (e in history.allWords) {
                if (e.count >= MIN_USER_COUNT) offer(e.display, 2 * BACKOFF + USER_WORD_BASE + log10(e.count.toDouble()))
            }
        }

        if (prefix.isEmpty() && candidates.size < limit && model != null) {
            for (id in model.topWords) offer(model.word(id), 2 * BACKOFF + model.unigramLogProb(id))
        }

        val ranked = candidates.values.sortedByDescending { it.score }.map { it.display }.take(limit).toMutableList()
        if (prefix.isNotEmpty() && ranked.none { it.equals(prefix, ignoreCase = true) }) {
            ranked.add(minOf(1, ranked.size), prefix)
            if (ranked.size > limit) ranked.removeAt(ranked.lastIndex)
        }
        return ranked
    }

    /** Records that the user finished [word] in [ctx] (whose partial is ignored). */
    fun learn(ctx: TypingContext, word: String) {
        if (word.none { it.isLetter() }) return
        history.learn(historyContext(ctx), displayForm(ctx, word))
    }

    /** Model ids of the two words before the cursor, with [NgramModel.START] at a boundary. */
    private fun modelContext(m: NgramModel, ctx: TypingContext): Pair<Int?, Int?> {
        val ids = ctx.previous.map { m.idOf(it) }
        val start = if (ctx.sentenceStart) NgramModel.START else null
        return when (ids.size) {
            0 -> null to start
            1 -> (if (ids[0] != null) start else null) to ids[0]
            else -> (if (ids[1] != null) ids[0] else null) to ids[1]
        }
    }

    private fun historyContext(ctx: TypingContext): String? =
        ctx.previous.lastOrNull() ?: if (ctx.sentenceStart) UserHistory.START else null

    /**
     * The casing to remember [word] by. A capital that only comes from starting a sentence
     * (or caps lock) says nothing about the word, so keep what is already known instead.
     */
    private fun displayForm(ctx: TypingContext, word: String): String {
        val shouting = word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() }
        val sentenceInitial = ctx.previous.isEmpty() && ctx.sentenceStart
        if (!shouting && !sentenceInitial) return word
        return history.word(word)?.display
            ?: model?.idOf(word)?.let { model.word(it) }
            ?: word.lowercase()
    }

    companion object {
        /** Stupid-backoff penalty per order, log10(0.4). */
        private val BACKOFF = log10(0.4)
        private const val ACCENT_MISMATCH = 0.5
        /** A word must be typed this often before it is suggested, so one-off typos aren't. */
        private const val MIN_USER_COUNT = 2
        /** A word the user typed twice scores like a fairly common word (zipf ~4.8). */
        private const val USER_WORD_BASE = -4.5

        private fun offerScored(display: String, score: Double, into: HashMap<String, Candidate>) {
            val key = display.lowercase()
            val c = into[key]
            if (c == null) into[key] = Candidate(display, score)
            else if (score > c.score) c.score = score
        }

        /**
         * [word] cased for the strip: following the typed [partial] while a word is being
         * typed, otherwise capitalised when shift is on ([capitalize]) or caps lock ([allCaps]).
         */
        fun applyCase(word: String, partial: String, capitalize: Boolean, allCaps: Boolean): String {
            if (word == partial) return word
            val upper = allCaps || (partial.length > 1 && partial.all { !it.isLetter() || it.isUpperCase() })
            if (upper) return word.uppercase()
            val initial = if (partial.isNotEmpty()) partial[0].isUpperCase() else capitalize
            return if (initial) word.replaceFirstChar { it.uppercaseChar() } else word
        }
    }
}
