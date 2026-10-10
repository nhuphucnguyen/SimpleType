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

    // ---- Questions and casing (VPN2 models: 4 punctuation + 3 casing logits per token) ----

    private val question = floatArrayOf(0f, 0f, 0f, 5f)

    /** 7-wide row: punctuation logits [O, COMMA, PERIOD, QUESTION] + casing [lower, Cap, UPPER]. */
    private fun row(punct: FloatArray, case: Int = 0) =
        FloatArray(7).also { r -> punct.copyInto(r); r[4 + case] = 5f }

    @Test
    fun `question label inserts a question mark`() {
        val p = punctuator { id -> if (id == 2) question else o }
        assertEquals("x a? y.", p.punctuate("x a y"))
    }

    @Test
    fun `a question at the end of the segment keeps its question mark`() {
        val p = punctuator { id -> if (id == 2) question else o }
        assertEquals("x a?", p.punctuate("x a"))
    }

    @Test
    fun `question bias tips a close call between period and question`() {
        val tie = floatArrayOf(0f, 0f, 3f, 3f)
        val toQuestion = Punctuator(tok, { ids -> Array(ids.size) { if (ids[it] == 2) tie else o } },
            DecisionRule.LEGACY.copy(questionBias = 0.5f))
        val toPeriod = Punctuator(tok, { ids -> Array(ids.size) { if (ids[it] == 2) tie else o } },
            DecisionRule.LEGACY.copy(questionBias = -0.5f))
        assertEquals("a? x.", toQuestion.punctuate("a x"))
        assertEquals("a. x.", toPeriod.punctuate("a x"))
    }

    @Test
    fun `casing outputs capitalise and upper-case words`() {
        val p = punctuator { id ->
            when (id) {
                2 -> row(floatArrayOf(2f, 0f, 0f, 0f), case = 1) // a -> Cap
                3 -> row(floatArrayOf(2f, 0f, 0f, 0f), case = 2) // b -> UPPER
                else -> row(floatArrayOf(2f, 0f, 0f, 0f))
            }
        }
        assertEquals("x Ab BB x.", p.punctuate("x ab bb x"))
    }

    @Test
    fun `lowercase prediction keeps the input casing`() {
        val p = punctuator { row(floatArrayOf(2f, 0f, 0f, 0f)) }
        assertEquals("X y.", p.punctuate("X y"))
    }

    @Test
    fun `continuation can report a question mark for the previous segment`() {
        val p = punctuator { id -> if (id == 2) question else o }
        assertEquals(Continuation('?', "y z."), p.continueAfter("x a.", "y z"))
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
