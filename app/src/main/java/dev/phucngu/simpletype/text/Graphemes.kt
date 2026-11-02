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

/**
 * The UTF-16 offset reached by moving [steps] user-perceived characters from [from] in [text]
 * (negative = backwards), stopping at either end. Like an arrow key, it never lands inside an
 * emoji or between a letter and its combining marks.
 */
fun moveByGraphemes(text: CharSequence, from: Int, steps: Int): Int {
    if (steps == 0 || text.isEmpty()) return from.coerceIn(0, text.length)
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text.toString())
    var pos = from.coerceIn(0, text.length)
    if (!iterator.isBoundary(pos)) pos = if (steps > 0) iterator.preceding(pos) else iterator.following(pos)
    repeat(kotlin.math.abs(steps)) {
        val next = if (steps > 0) iterator.following(pos) else iterator.preceding(pos)
        if (next == BreakIterator.DONE) return pos
        pos = next
    }
    return pos
}
