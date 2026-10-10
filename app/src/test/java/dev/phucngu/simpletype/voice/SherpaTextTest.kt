package dev.phucngu.simpletype.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SherpaTextTest {

    // Vietnamese Zipformer: uppercase, punctuation-free output.

    @Test
    fun `vietnamese is lowercased, capitalised and given a period`() {
        assertEquals("Âm lượng tv giảm.", SherpaText.format("ÂM LƯỢNG TV GIẢM", SherpaModel.VIETNAMESE))
    }

    @Test
    fun `vietnamese lowercases diacritic capitals`() {
        assertEquals("Đường ưu tiên.", SherpaText.format("ĐƯỜNG ƯU TIÊN", SherpaModel.VIETNAMESE))
    }

    // Vietnamese with the punctuation model: sentence starts are capitalised.

    private val fakePunct: (String) -> String = { it.replace("ơi", "ơi,").replace("không", "không.") }

    @Test
    fun `vietnamese is punctuated by the model and every sentence capitalised`() {
        assertEquals(
            "Anh ơi, có rảnh không. Em nhờ chút.",
            SherpaText.format("ANH ƠI CÓ RẢNH KHÔNG EM NHỜ CHÚT", SherpaModel.VIETNAMESE, fakePunct),
        )
    }

    @Test
    fun `vietnamese punctuated output keeps a single final period`() {
        assertEquals(
            "Có rảnh không.",
            SherpaText.format("CÓ RẢNH KHÔNG", SherpaModel.VIETNAMESE, fakePunct),
        )
    }

    @Test
    fun `a failing punctuator falls back to the plain format`() {
        assertEquals(
            "Âm lượng tv giảm.",
            SherpaText.format("ÂM LƯỢNG TV GIẢM", SherpaModel.VIETNAMESE) { error("boom") },
        )
    }

    @Test
    fun `english ignores the punctuator`() {
        assertEquals("Are you there?", SherpaText.format("Are you there?", SherpaModel.ENGLISH) { "x" })
    }

    // Vietnamese continuation: the model decides how the previous segment should end.

    private fun punctAnswering(boundary: Char?) = object : TextPunctuator {
        override fun punctuate(text: String) = "$text."
        override fun continueAfter(previous: String, text: String) = Continuation(boundary, "$text.")
    }

    @Test
    fun `continuation joined by a comma starts lowercase`() {
        assertEquals(
            Segment("chiều nay anh rảnh không.", SegmentJoin.COMMA),
            SherpaText.formatAfter("CHIỀU NAY ANH RẢNH KHÔNG", SherpaModel.VIETNAMESE, punctAnswering(','), "Anh ơi."),
        )
    }

    @Test
    fun `continuation with no boundary mark continues the sentence`() {
        assertEquals(
            Segment("nhà rồi.", SegmentJoin.CONTINUE),
            SherpaText.formatAfter("NHÀ RỒI", SherpaModel.VIETNAMESE, punctAnswering(null), "Em về đến."),
        )
    }

    @Test
    fun `continuation after a real sentence end starts a new sentence`() {
        assertEquals(
            Segment("Em đi đây.", SegmentJoin.NONE),
            SherpaText.formatAfter("EM ĐI ĐÂY", SherpaModel.VIETNAMESE, punctAnswering('.'), "Xong rồi."),
        )
    }

    @Test
    fun `no previous segment formats as usual`() {
        assertEquals(
            Segment("Em đi đây.", SegmentJoin.NONE),
            SherpaText.formatAfter("EM ĐI ĐÂY", SherpaModel.VIETNAMESE, punctAnswering(','), null),
        )
    }

    @Test
    fun `failing continuation falls back to a new sentence`() {
        val broken = object : TextPunctuator {
            override fun punctuate(text: String) = error("boom")
            override fun continueAfter(previous: String, text: String) = error("boom")
        }
        assertEquals(
            Segment("Em đi đây.", SegmentJoin.NONE),
            SherpaText.formatAfter("EM ĐI ĐÂY", SherpaModel.VIETNAMESE, broken, "Xong."),
        )
    }

    @Test
    fun `english never joins`() {
        assertEquals(
            Segment("Okay.", SegmentJoin.NONE),
            SherpaText.formatAfter("okay", SherpaModel.ENGLISH, punctAnswering(','), "Hi."),
        )
    }

    // English Parakeet: already cased and punctuated — must not be lowercased.

    @Test
    fun `english keeps the model's casing and punctuation`() {
        assertEquals(
            "Well, I don't wish to see it, observed Phoebe.",
            SherpaText.format("Well, I don't wish to see it, observed Phoebe.", SherpaModel.ENGLISH),
        )
    }

    @Test
    fun `english keeps question marks`() {
        assertEquals("Are you there?", SherpaText.format("Are you there?", SherpaModel.ENGLISH))
    }

    @Test
    fun `english without terminal punctuation gets a period and capital`() {
        assertEquals("Okay.", SherpaText.format(" okay ", SherpaModel.ENGLISH))
    }

    @Test
    fun `blank output stays empty`() {
        assertEquals("", SherpaText.format("   ", SherpaModel.ENGLISH))
        assertEquals("", SherpaText.format("", SherpaModel.VIETNAMESE))
    }
}
