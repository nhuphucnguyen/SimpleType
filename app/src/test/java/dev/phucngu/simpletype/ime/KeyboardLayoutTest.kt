package dev.phucngu.simpletype.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/** Geometry checks for the static layouts. Pure data, no Android runtime needed. */
class KeyboardLayoutTest {

    private fun spaceCentered(row: KeyboardRow) {
        val i = row.keys.indexOfFirst { it.code == KeyCode.SPACE }
        require(i >= 0) { "row has no space key" }
        val left = row.keys.take(i).sumOf { it.weight.toDouble() }
        val right = row.keys.drop(i + 1).sumOf { it.weight.toDouble() }
        assertEquals("space must be horizontally centered (equal weight each side)", left, right, 0.001)
    }

    @Test fun alpha_bottom_row_space_is_centered() = spaceCentered(KeyboardLayouts.qwerty().rows.last())

    @Test fun symbols_bottom_row_space_is_centered() = spaceCentered(KeyboardLayouts.symbols().rows.last())

    @Test fun symbols_alt_bottom_row_space_is_centered() = spaceCentered(KeyboardLayouts.symbolsAlt().rows.last())

    @Test fun comma_long_press_opens_emoji() {
        val row = KeyboardLayouts.qwerty().rows.last()
        val comma = row.keys.first { it.code == ','.code }
        assertEquals(KeyCode.EMOJI, comma.longPressCode)
    }
}
