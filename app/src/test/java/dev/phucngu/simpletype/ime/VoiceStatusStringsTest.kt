package dev.phucngu.simpletype.ime

import dev.phucngu.simpletype.R
import dev.phucngu.simpletype.voice.VoiceLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceStatusStringsTest {

    @Test fun english_listening_status_uses_english_message() =
        assertEquals(R.string.voice_listening_en, VoiceLanguage.ENGLISH.listeningStatusRes())

    @Test fun vietnamese_listening_status_uses_vietnamese_message() =
        assertEquals(R.string.voice_listening_vi, VoiceLanguage.VIETNAMESE.listeningStatusRes())
}
