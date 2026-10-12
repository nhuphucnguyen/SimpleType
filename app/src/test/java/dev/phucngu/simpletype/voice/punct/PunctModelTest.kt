package dev.phucngu.simpletype.voice.punct

import org.junit.Assert.assertEquals
import org.junit.Test

class PunctModelTest {

    @Test
    fun `logits match the python int8 reference`() {
        val model = PunctTestAssets.model
        for (g in PunctTestAssets.golden) {
            val got = model.logits(g.ids, g.pause)
            assertEquals(g.text, g.ids.size, got.size)
            assertEquals(g.text, model.punctClasses + model.caseClasses, g.width)
            for (i in g.ids.indices) for (c in 0 until g.width) {
                assertEquals("${g.text} [$i,$c]", g.logits[i * g.width + c], got[i][c], 2e-3f)
            }
        }
    }

    @Test
    fun `empty input gives no rows`() {
        assertEquals(0, PunctTestAssets.model.logits(IntArray(0)).size)
    }
}
