package dev.phucngu.simpletype.ime

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardViewMetricsTest {

    @Test fun number_hint_is_centered_and_one_dp_from_key_top() {
        val keyRect = RectF(10f, 20f, 50f, 80f)
        val position = calculateNumberHintPosition(
            keyRect = keyRect,
            densityFloat = 2f,
            fontAscent = -8f,
        )

        assertEquals(30f, position.x, 0f)
        assertEquals(22f, position.y + -8f, 0f)
    }

    @Test fun letter_moves_down_two_dp_when_number_hint_is_visible() {
        val baseline = calculateNumberHintedTextBaseline(
            centeredBaseline = 40f,
            keyBottom = 80f,
            densityFloat = 2f,
            fontDescent = 5f,
        )

        assertEquals(44f, baseline, 0f)
    }

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

    @Test fun numeric_delete_and_enter_span_two_rows() {
        val placements = calculatePlacements(
            widthPx = 400f,
            keyboard = KeyboardLayouts.numeric(),
            metrics = KeyboardMetrics.of(50f, 4f, 4f),
            densityFloat = 1f,
            vPadPx = 0f,
        )

        val delete = placements.first { it.key.code == KeyCode.DELETE }.rect
        val enter = placements.first { it.key.code == KeyCode.ENTER }.rect
        val doubleZero = placements.first { it.key.code == KeyCode.DOUBLE_ZERO }.rect

        assertEquals(100f, delete.height(), 0f)
        assertEquals(100f, enter.height(), 0f)
        assertEquals(300f, delete.left, 0f)
        assertEquals(300f, enter.left, 0f)
        assertEquals(0f, doubleZero.left, 0f)
        assertEquals(150f, doubleZero.top, 0f)
    }
}
