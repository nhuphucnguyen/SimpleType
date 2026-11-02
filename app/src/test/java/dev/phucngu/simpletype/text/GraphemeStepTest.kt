package dev.phucngu.simpletype.text

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphemeStepTest {

    @Test fun `moves by plain characters`() {
        assertEquals(3, moveByGraphemes("hello", from = 1, steps = 2))
        assertEquals(0, moveByGraphemes("hello", from = 1, steps = -1))
    }

    @Test fun `clamps at both ends`() {
        assertEquals(5, moveByGraphemes("hello", from = 3, steps = 10))
        assertEquals(0, moveByGraphemes("hello", from = 3, steps = -10))
    }

    @Test fun `steps over an emoji surrogate pair as one character`() {
        val text = "a😀b" // a😀b
        assertEquals(3, moveByGraphemes(text, from = 1, steps = 1))
        assertEquals(1, moveByGraphemes(text, from = 3, steps = -1))
    }

    @Test fun `steps over a decomposed Vietnamese letter as one character`() {
        val text = "viết" // việt with combining circumflex and acute
        assertEquals(5, moveByGraphemes(text, from = 2, steps = 1))
        assertEquals(2, moveByGraphemes(text, from = 5, steps = -1))
    }

    @Test fun `zero steps stays put`() {
        assertEquals(2, moveByGraphemes("hello", from = 2, steps = 0))
    }
}
