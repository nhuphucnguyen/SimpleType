package dev.phucngu.simpletype.text

import java.text.BreakIterator

/**
 * Length in UTF-16 chars of the last user-perceived character in [text], so backspace removes a
 * whole emoji (surrogate pairs, skin tones, ZWJ sequences, flags) or a letter with its combining
 * marks instead of leaving half a character behind.
 */
fun lastGraphemeLength(text: CharSequence): Int {
    if (text.isEmpty()) return 0
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text.toString())
    val end = iterator.last()
    val start = iterator.previous()
    return if (start == BreakIterator.DONE) end else end - start
}
