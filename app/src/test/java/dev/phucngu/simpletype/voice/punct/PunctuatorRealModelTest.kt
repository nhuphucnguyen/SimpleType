package dev.phucngu.simpletype.voice.punct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** End to end with the shipped model. */
class PunctuatorRealModelTest {

    private fun punctuate(text: String) = PunctTestAssets.punctuator.punctuate(text)

    @Test
    fun `golden cases match the python int8 reference`() {
        for (g in PunctTestAssets.golden) assertEquals(g.text, g.expected, punctuate(g.text))
    }

    @Test
    fun `questions end with a question mark`() {
        assertEquals("mai mấy giờ mình đi?", punctuate("mai mấy giờ mình đi"))
        assertTrue(punctuate("anh ơi chiều nay anh có rảnh không em muốn nhờ anh một việc").contains("không? em"))
    }

    @Test
    fun `greetings are not questions`() {
        assertEquals("xin chào.", punctuate("xin chào"))
        assertEquals("cảm ơn.", punctuate("cảm ơn"))
    }

    @Test
    fun `names are capitalised`() {
        val out = punctuate("chị lan ơi tuần sau em bay vào thành phố hồ chí minh chị có cần gửi gì không")
        assertTrue(out, out.startsWith("chị Lan ơi,"))
        assertTrue(out, out.contains("Hồ Chí Minh"))
        assertTrue(out, out.endsWith("không?"))
    }

    @Test
    fun `a pause after a vocative becomes a comma`() {
        val c = PunctTestAssets.punctuator.continueAfter(
            "Anh ơi.", "chiều nay anh có rảnh không em muốn nhờ anh một việc",
        )
        assertEquals(',', c.boundary)
        assertTrue(c.text, c.text.startsWith("chiều nay anh có rảnh không?"))
    }

    @Test
    fun `a pause after a finished sentence stays a period`() {
        val c = PunctTestAssets.punctuator.continueAfter("Anh ơi, chiều nay anh có rảnh không?", "em muốn nhờ anh một việc")
        assertTrue("${c.boundary}", c.boundary == '.' || c.boundary == '?')
    }
}
