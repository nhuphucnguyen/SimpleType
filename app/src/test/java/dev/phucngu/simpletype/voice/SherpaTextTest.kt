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
