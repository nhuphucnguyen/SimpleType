package dev.phucngu.simpletype.ime

import androidx.annotation.StringRes
import dev.phucngu.simpletype.R
import dev.phucngu.simpletype.voice.VoiceLanguage

@StringRes
internal fun VoiceLanguage.listeningStatusRes(): Int = when (this) {
    VoiceLanguage.ENGLISH -> R.string.voice_listening_en
    VoiceLanguage.VIETNAMESE -> R.string.voice_listening_vi
}
