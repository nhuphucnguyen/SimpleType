package dev.phucngu.simpletype.voice.punct

import dev.phucngu.simpletype.voice.Continuation
import org.junit.Assert.assertEquals
import org.junit.Test

/** End to end with the shipped model; expectations are the Python int8 reference output. */
class PunctuatorRealModelTest {

    private fun punctuate(text: String) = PunctTestAssets.punctuator.punctuate(text)

    @Test
    fun `dictated messages match the python reference`() {
        assertEquals(
            "anh ơi, chiều nay anh có rảnh không. em muốn nhờ anh một việc.",
            punctuate("anh ơi chiều nay anh có rảnh không em muốn nhờ anh một việc"),
        )
        assertEquals(
            "ừ, được rồi, để anh xem lại rồi. báo em sau nha.",
            punctuate("ừ được rồi để anh xem lại rồi báo em sau nha"),
        )
        assertEquals(
            "hôm qua công ty có cuộc họp quan trọng, sếp nói tháng sau sẽ tăng lương cho mọi người.",
            punctuate("hôm qua công ty có cuộc họp quan trọng sếp nói tháng sau sẽ tăng lương cho mọi người"),
        )
        assertEquals("xin chào.", punctuate("xin chào"))
    }

    @Test
    fun `a pause after a vocative becomes a comma`() {
        val c = PunctTestAssets.punctuator.continueAfter(
            "Anh ơi.", "chiều nay anh có rảnh không em muốn nhờ anh một việc",
        )
        assertEquals(Continuation(',', "chiều nay anh có rảnh không. em muốn nhờ anh một việc."), c)
    }

    @Test
    fun `a pause after a finished question stays a period`() {
        val c = PunctTestAssets.punctuator.continueAfter("Anh ơi, chiều nay anh có rảnh không.", "em muốn nhờ anh một việc")
        assertEquals('.', c.boundary)
    }
}
