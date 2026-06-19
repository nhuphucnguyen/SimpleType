package dev.phucngu.simpletype.ime

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardViewMetricsTest {

    private fun placementsBottom(metrics: KeyboardMetrics): Float {
        val placements = calculatePlacements(
            widthPx = 1080f,
            keyboard = KeyboardLayouts.qwerty(showDedicatedNumberRow = false),
            metrics = metrics,
            densityFloat = 2.625f,
            vPadPx = 21f
        )
        return placements.maxOf { it.rect.bottom }
    }

    @Test fun larger_row_height_makes_a_taller_keyboard() {
        val baseBottom = placementsBottom(KeyboardMetrics.DEFAULT)
        val tallerBottom = placementsBottom(KeyboardMetrics.of(KeyboardMetrics.ROW_HEIGHT_MAX, 4f, 4f))

        assertTrue("taller bottom=$tallerBottom should exceed base bottom=$baseBottom", tallerBottom > baseBottom)
    }
}
