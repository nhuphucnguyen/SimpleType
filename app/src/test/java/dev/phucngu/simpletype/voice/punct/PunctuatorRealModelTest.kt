package dev.phucngu.simpletype.voice.punct

import dev.phucngu.simpletype.voice.SegmentJoin
import dev.phucngu.simpletype.voice.SherpaModel
import dev.phucngu.simpletype.voice.SherpaText
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
    }

    @Test
    fun `a question followed by more speech without a pause takes a comma`() {
        // the owner's preference: "anh rảnh không, em nhờ…" in one breath; '?' only after a pause
        assertTrue(punctuate("anh ơi chiều nay anh có rảnh không em muốn nhờ anh một việc").contains("không, em"))
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
    }

    @Test
    fun `a pause after a question keeps the question mark`() {
        val s = SherpaText.formatAfter(
            "EM MUỐN NHỜ ANH MỘT VIỆC", SherpaModel.VIETNAMESE, PunctTestAssets.punctuator,
            "Anh ơi, chiều nay anh có rảnh không?",
        )
        assertTrue("${s.join}", s.join == SegmentJoin.NONE)
        assertTrue(s.text, s.text.startsWith("Em muốn"))
    }
}
