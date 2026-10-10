package dev.phucngu.simpletype.voice.punct

import org.junit.Assert.assertEquals
import org.junit.Test

class PunctModelTest {

    @Test
    fun `logits match the python int8 reference`() {
        for ((text, ids, expected) in PunctTestAssets.golden) {
            val got = PunctTestAssets.model.logits(ids)
            assertEquals(text, ids.size, got.size)
            for (i in ids.indices) for (c in 0 until 3) {
                assertEquals("$text [$i,$c]", expected[i * 3 + c], got[i][c], 2e-3f)
            }
        }
    }

    @Test
    fun `empty input gives no rows`() {
        assertEquals(0, PunctTestAssets.model.logits(IntArray(0)).size)
    }
}
