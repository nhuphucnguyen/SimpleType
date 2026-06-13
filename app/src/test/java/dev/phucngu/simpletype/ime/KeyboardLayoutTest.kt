package dev.phucngu.simpletype.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun alpha_bottom_row_space_is_centered() = spaceCentered(KeyboardLayouts.qwerty(false).rows.last())

    @Test fun qwerty_with_dedicated_number_row_adds_extra_row() {
        val standard = KeyboardLayouts.qwerty(showDedicatedNumberRow = false)
        val expanded = KeyboardLayouts.qwerty(showDedicatedNumberRow = true)

        assertEquals("standard qwerty has 4 rows", 4, standard.rows.size)
        assertEquals("expanded qwerty has 5 rows", 5, expanded.rows.size)

        val firstRow = expanded.rows.first()
        val labels = firstRow.keys.map { it.label }
        assertEquals("first row must be numbers 1-0", "1234567890".map { it.toString() }, labels)
        assertTrue("number row keys must not have number hints themselves",
            firstRow.keys.all { it.numberHint == null })
    }

    @Test fun symbols_bottom_row_space_is_centered() = spaceCentered(KeyboardLayouts.symbols().rows.last())

    @Test fun symbols_alt_bottom_row_space_is_centered() = spaceCentered(KeyboardLayouts.symbolsAlt().rows.last())

    @Test fun comma_long_press_opens_emoji() {
        val row = KeyboardLayouts.qwerty(false).rows.last()
        val comma = row.keys.first { it.code == ','.code }
        assertEquals(KeyCode.EMOJI, comma.longPressCode)
    }

    private fun KeyboardRow.has(c: Char) = keys.any { it.code == c.code }
    private fun KeyboardRow.hasCode(code: Int) = keys.any { it.code == code }

    @Test fun symbols_page1_switches_to_page2() =
        assertTrue("page 1 must have the =\\< switch", KeyboardLayouts.symbols().rows[2].hasCode(KeyCode.SYMBOLS_ALT))

    @Test fun symbols_page2_switches_back() =
        assertTrue("page 2 must have the ?123 switch", KeyboardLayouts.symbolsAlt().rows[2].hasCode(KeyCode.SYMBOLS))

    @Test fun top_row_doubles_as_number_row() {
        // When dedicated row is off, the letters row is the top one (first).
        val top = KeyboardLayouts.qwerty(showDedicatedNumberRow = false).rows.first()
        val hints = top.keys.map { it.numberHint }
        assertEquals("qwertyuiop must carry number hints 1234567890",
            "1234567890".toList(), hints)

        // When dedicated row is on, the letters row is the second row.
        val lettersRow = KeyboardLayouts.qwerty(showDedicatedNumberRow = true).rows[1]
        assertEquals("qwertyuiop still carries hints when row is expanded",
            "1234567890".toList(), lettersRow.keys.map { it.numberHint })
    }

    @Test fun only_alpha_letters_row_carries_number_hints() {
        // Other rows (and the symbol layouts, which already are digits) get no swipe-down hint.
        val layout = KeyboardLayouts.qwerty(showDedicatedNumberRow = true)
        val nonLetterRows = listOf(layout.rows[0]) + layout.rows.drop(2)
        assertTrue("non-letter rows must not carry number hints",
            nonLetterRows.all { row -> row.keys.all { it.numberHint == null } })

        assertTrue("symbol pages must not carry number hints",
            KeyboardLayouts.symbols().rows.all { row -> row.keys.all { it.numberHint == null } })
    }

    @Test fun symbols_arrangement_matches_gboard() {
        val p1 = KeyboardLayouts.symbols().rows
        assertTrue("page1 row2 should hold đ", p1[1].has('đ'))
        val p2 = KeyboardLayouts.symbolsAlt().rows
        assertTrue("page2 row2 should hold \$", p2[1].has('$'))
        assertTrue("page2 row2 should hold backslash", p2[1].has('\\'))
        assertTrue("page2 row3 should hold ✓", p2[2].has('✓'))
        assertTrue("page2 bottom should hold <", p2[3].has('<'))
        assertTrue("page2 bottom should hold >", p2[3].has('>'))
    }
}
