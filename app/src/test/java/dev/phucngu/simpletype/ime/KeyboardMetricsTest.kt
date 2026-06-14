package dev.phucngu.simpletype.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure clamping/defaults for the user-adjustable keyboard sizing. */
class KeyboardMetricsTest {

    @Test fun defaults_match_current_dimens() {
        assertEquals(52f, KeyboardMetrics.DEFAULT.rowHeightDp, 0f)
        assertEquals(4f, KeyboardMetrics.DEFAULT.gapHorizontalDp, 0f)
        assertEquals(4f, KeyboardMetrics.DEFAULT.gapVerticalDp, 0f)
        assertEquals(8f, KeyboardMetrics.DEFAULT.bottomPaddingDp, 0f)
        assertEquals(true, KeyboardMetrics.DEFAULT.showNumberRow)
        assertEquals(false, KeyboardMetrics.DEFAULT.showDedicatedNumberRow)
        assertEquals(false, KeyboardMetrics.DEFAULT.showSymbolHints)
    }

    @Test fun symbol_hints_force_the_number_row_off() {
        val m = KeyboardMetrics.of(
            rowHeightDp = 52f, gapHorizontalDp = 4f, gapVerticalDp = 4f,
            showNumberRow = true, showSymbolHints = true,
        )
        assertEquals("symbol hints take the corner/swipe slot", true, m.showSymbolHints)
        assertEquals("so the number hints must be forced off", false, m.showNumberRow)
    }

    @Test fun number_hints_hidden_when_dedicated_row_shown() {
        val withDedicated = KeyboardMetrics.of(
            rowHeightDp = 52f, gapHorizontalDp = 4f, gapVerticalDp = 4f,
            showNumberRow = true, showDedicatedNumberRow = true,
        )
        assertEquals("hint preference is preserved", true, withDedicated.showNumberRow)
        assertEquals("but hints don't render alongside the dedicated row",
            false, withDedicated.numberHintsVisible)

        val withoutDedicated = KeyboardMetrics.of(
            rowHeightDp = 52f, gapHorizontalDp = 4f, gapVerticalDp = 4f,
            showNumberRow = true, showDedicatedNumberRow = false,
        )
        assertEquals("hints render when the dedicated row is off",
            true, withoutDedicated.numberHintsVisible)
    }

    @Test fun number_row_survives_when_symbol_hints_off() {
        val m = KeyboardMetrics.of(
            rowHeightDp = 52f, gapHorizontalDp = 4f, gapVerticalDp = 4f,
            showNumberRow = true, showSymbolHints = false,
        )
        assertEquals(true, m.showNumberRow)
        assertEquals(false, m.showSymbolHints)
    }

    @Test fun of_coerces_into_allowed_ranges() {
        val tooSmall = KeyboardMetrics.of(
            rowHeightDp = 10f, gapHorizontalDp = 0f, gapVerticalDp = -5f,
            bottomPaddingDp = -9f, showNumberRow = false, showDedicatedNumberRow = true
        )
        assertEquals(KeyboardMetrics.ROW_HEIGHT_MIN, tooSmall.rowHeightDp, 0f)
        assertEquals(KeyboardMetrics.GAP_MIN, tooSmall.gapHorizontalDp, 0f)
        assertEquals(KeyboardMetrics.GAP_MIN, tooSmall.gapVerticalDp, 0f)
        assertEquals(KeyboardMetrics.BOTTOM_PAD_MIN, tooSmall.bottomPaddingDp, 0f)
        assertEquals(false, tooSmall.showNumberRow)
        assertEquals(true, tooSmall.showDedicatedNumberRow)

        val tooBig = KeyboardMetrics.of(rowHeightDp = 999f, gapHorizontalDp = 999f, gapVerticalDp = 999f, bottomPaddingDp = 999f)
        assertEquals(KeyboardMetrics.ROW_HEIGHT_MAX, tooBig.rowHeightDp, 0f)
        assertEquals(KeyboardMetrics.GAP_MAX, tooBig.gapHorizontalDp, 0f)
        assertEquals(KeyboardMetrics.GAP_MAX, tooBig.gapVerticalDp, 0f)
        assertEquals(KeyboardMetrics.BOTTOM_PAD_MAX, tooBig.bottomPaddingDp, 0f)
    }

    @Test fun of_keeps_in_range_values() {
        val m = KeyboardMetrics.of(rowHeightDp = 60f, gapHorizontalDp = 6f, gapVerticalDp = 8f)
        assertEquals(60f, m.rowHeightDp, 0f)
        assertEquals(6f, m.gapHorizontalDp, 0f)
        assertEquals(8f, m.gapVerticalDp, 0f)
    }
}
