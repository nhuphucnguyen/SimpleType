package dev.phucngu.simpletype.ime

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardKeySizeTest {

    private fun widthOf(placements: List<Placement>, c: Char): Float {
        for (p in placements) {
            if (p.key.code == c.code) {
                return p.rect.width()
            }
        }
        error("no placement for '$c'")
    }

    @Test fun letter_keys_are_equal_width_across_rows() {
        val placements = calculatePlacements(
            widthPx = 1080f,
            keyboard = KeyboardLayouts.qwerty(showDedicatedNumberRow = false),
            metrics = KeyboardMetrics.DEFAULT,
            densityFloat = 2.625f,
            vPadPx = 21f
        )
        val q = widthOf(placements, 'q') // row 1 (10 keys)
        val a = widthOf(placements, 'a') // row 2 (9 keys, must be indented to match)
        val z = widthOf(placements, 'z') // row 3 (between shift & delete)
        assertEquals("row2 letter width should match row1", q, a, 0.5f)
        assertEquals("row3 letter width should match row1", q, z, 0.5f)
    }
}
