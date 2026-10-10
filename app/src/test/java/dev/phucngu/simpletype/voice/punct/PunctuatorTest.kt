package dev.phucngu.simpletype.voice.punct

import dev.phucngu.simpletype.voice.Continuation
import org.junit.Assert.assertEquals
import org.junit.Test

class PunctuatorTest {

    // One token per letter, so word i starts at a known position.
    private val tok = BpeTokenizer(
        vocab = listOf("[PAD]", "[UNK]") + ('a'..'z').map { it.toString() },
        merges = emptyList(),
    )

    private val o = floatArrayOf(2f, 0f, 0f) // p(break) 0.21, p(period) 0.11 > t_end_period
    private val comma = floatArrayOf(0f, 5f, 0f)
    private val period = floatArrayOf(0f, 0f, 5f)

    /** Fake model that answers with [rowFor] per token id. */
    private fun punctuator(rowFor: (Int) -> FloatArray) =
        Punctuator(tok, { ids -> Array(ids.size) { rowFor(ids[it]) } })

    @Test
    fun `inserts commas and periods after the predicted words`() {
        // a -> comma, b -> period, everything else -> none
        val p = punctuator { id -> when (id) { 2 -> comma; 3 -> period; else -> o } }
        assertEquals("x a, y b. z.", p.punctuate("x a y b z"))
    }

    @Test
    fun `last word always closes with a period, never a comma`() {
        val p = punctuator { id -> if (id == 2) comma else o }
        assertEquals("x a.", p.punctuate("x a"))
        assertEquals("x.", p.punctuate("x"))
    }

    @Test
    fun `comma bias tips a close call towards comma`() {
        // p(comma)=p(period): bias 0.5 makes it a comma
        val p = punctuator { id -> if (id == 2) floatArrayOf(0f, 3f, 3f) else o }
        assertEquals("a, x.", p.punctuate("a x"))
    }

    @Test
    fun `weak break probability stays unpunctuated`() {
        // p(break) ~ 0.47 < t_break 0.6
        val p = punctuator { id -> if (id == 2) floatArrayOf(1.5f, 1.1f, 0f) else o }
        assertEquals("a x.", p.punctuate("a x"))
    }

    @Test
    fun `existing punctuation is stripped before the model sees it`() {
        val p = punctuator { o }
        assertEquals("x y.", p.punctuate("x, y."))
    }

    @Test
    fun `the model sees lowercase and the output keeps casing`() {
        val p = punctuator { id -> if (id == 2) comma else o }
        assertEquals("A, x.", p.punctuate("A x"))
    }

    @Test
    fun `long input is split into windows`() {
        val seen = mutableListOf<Int>()
        val p = Punctuator(tok, { ids -> seen += ids.size; Array(ids.size) { o } })
        val words = List(300) { "x" }.joinToString(" ")
        assertEquals("$words.", p.punctuate(words))
        assert(seen.size > 1 && seen.all { it <= Punctuator.MAX_LEN }) { seen.toString() }
        assertEquals(300, seen.sum())
    }

    @Test
    fun `blank input is returned as is`() {
        assertEquals("", punctuator { o }.punctuate("   ").trim())
    }

    // ---- Continuation: a segment spoken right after the previous one ----

    @Test
    fun `continuation reports the mark that should end the previous segment`() {
        val p = punctuator { id -> when (id) { 2 -> comma; 3 -> period; else -> o } }
        // previous ends with "a" (comma), "b" (period) or "x" (nothing)
        assertEquals(Continuation(',', "y z."), p.continueAfter("x a.", "y z"))
        assertEquals(Continuation('.', "y z."), p.continueAfter("x b.", "y z"))
        assertEquals(Continuation(null, "y z."), p.continueAfter("a x.", "y z"))
    }

    @Test
    fun `continuation output covers only the new segment`() {
        val p = punctuator { id -> if (id == 2) comma else o }
        assertEquals(Continuation(null, "a, y."), p.continueAfter("x x x", "a y"))
    }

    @Test
    fun `continuation sees the previous words as context`() {
        val seen = mutableListOf<Int>()
        val p = Punctuator(tok, { ids -> seen += ids.size; Array(ids.size) { o } })
        p.continueAfter("x x x", "y")
        assertEquals(listOf(4), seen)
    }

    @Test
    fun `empty previous segment is a plain punctuation`() {
        val p = punctuator { o }
        assertEquals(Continuation('.', "y z."), p.continueAfter("", "y z"))
    }
}
