package dev.phucngu.simpletype.predict

/**
 * Words and word pairs the user actually types, so predictions adapt to them (names,
 * slang, habitual phrases), like Gboard's personalised suggestions. Kept on the device
 * only, one instance per language. Not thread-safe: use it from the main thread.
 *
 * Keys are lowercase; the casing the user last typed is kept for display ("Phúc").
 */
class UserHistory {

    class Entry(var display: String, var count: Int)

    private val words = HashMap<String, Entry>()
    /** previous word key (or [START]) -> next word key -> entry. */
    private val pairs = HashMap<String, HashMap<String, Entry>>()
    private var pairCount = 0

    var dirty = false
        private set

    fun word(word: String): Entry? = words[word.lowercase()]

    fun followers(previous: String): Map<String, Entry> = pairs[previous.lowercase()] ?: emptyMap()

    val allWords: Collection<Entry> get() = words.values

    /** Records that the user finished [word] after [previous] (null when the context broke). */
    fun learn(previous: String?, word: String) {
        if (word.isEmpty() || word.length > MAX_WORD_LENGTH) return
        val key = word.lowercase()
        words.getOrPut(key) { Entry(word, 0) }.apply { display = word; count++ }
        if (previous != null) {
            val followers = pairs.getOrPut(previous.lowercase()) { HashMap() }
            val entry = followers[key]
            if (entry == null) {
                followers[key] = Entry(word, 1)
                pairCount++
            } else {
                entry.display = word
                entry.count++
            }
        }
        dirty = true
        prune()
    }

    /** Drops the rarest entries once the history outgrows its budget. */
    private fun prune() {
        if (words.size > MAX_WORDS) {
            val keep = words.entries.sortedByDescending { it.value.count }.take(MAX_WORDS * 3 / 4)
            words.clear()
            keep.forEach { words[it.key] = it.value }
        }
        if (pairCount > MAX_PAIRS) {
            val all = pairs.flatMap { (prev, f) -> f.map { Triple(prev, it.key, it.value) } }
                .sortedByDescending { it.third.count }
                .take(MAX_PAIRS * 3 / 4)
            pairs.clear()
            all.forEach { (prev, key, entry) -> pairs.getOrPut(prev) { HashMap() }[key] = entry }
            pairCount = all.size
        }
    }

    fun serialize(): String {
        dirty = false
        val sb = StringBuilder()
        for (e in words.values) sb.append("u\t").append(e.display).append('\t').append(e.count).append('\n')
        for ((prev, followers) in pairs) {
            for (e in followers.values) {
                sb.append("b\t").append(prev).append('\t').append(e.display).append('\t').append(e.count).append('\n')
            }
        }
        return sb.toString()
    }

    companion object {
        const val START = "<s>"
        private const val MAX_WORD_LENGTH = 32
        private const val MAX_WORDS = 4000
        private const val MAX_PAIRS = 12000

        fun parse(text: String): UserHistory {
            val h = UserHistory()
            for (line in text.lineSequence()) {
                val cols = line.split('\t')
                when {
                    cols[0] == "u" && cols.size == 3 -> {
                        val count = cols[2].toIntOrNull() ?: continue
                        h.words[cols[1].lowercase()] = Entry(cols[1], count)
                    }
                    cols[0] == "b" && cols.size == 4 -> {
                        val count = cols[3].toIntOrNull() ?: continue
                        h.pairs.getOrPut(cols[1]) { HashMap() }[cols[2].lowercase()] = Entry(cols[2], count)
                        h.pairCount++
                    }
                }
            }
            return h
        }
    }
}
