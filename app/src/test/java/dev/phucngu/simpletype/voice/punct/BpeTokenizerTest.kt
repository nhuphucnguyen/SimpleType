package dev.phucngu.simpletype.voice.punct

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BpeTokenizerTest {

    @Test
    fun `matches the training tokenizer ids on golden texts`() {
        for ((text, ids, _) in PunctTestAssets.golden) {
            val got = text.split(' ').flatMap { PunctTestAssets.tokenizer.bpeWord(it).toList() }
            assertArrayEquals(text, ids, got.toIntArray())
        }
    }

    @Test
    fun `merges by rank and drops characters missing from the vocab`() {
        val tok = BpeTokenizer(
            vocab = listOf("[PAD]", "[UNK]", "a", "b", "c", "ab", "abc"),
            merges = listOf("a b", "ab c"),
        )
        assertArrayEquals(intArrayOf(6), tok.bpeWord("abc"))
        assertArrayEquals(intArrayOf(5), tok.bpeWord("a킴b"))
        assertArrayEquals(intArrayOf(), tok.bpeWord("킴"))
        assertEquals(0, tok.padId)
    }
}
