package dev.phucngu.simpletype.ime

import dev.phucngu.simpletype.ime.keyboard.layout.NumericKeyboardLayout
import dev.phucngu.simpletype.ime.keyboard.layout.QwertyKeyboardLayout
import dev.phucngu.simpletype.ime.keyboard.model.Key
import dev.phucngu.simpletype.ime.keyboard.model.KeyCode

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

    @Test fun hint_is_centered_and_one_dp_from_key_top() {
        val keyRect = RectF(10f, 20f, 50f, 80f)
        val position = calculateHintPosition(
            keyRect = keyRect,
            densityFloat = 2f,
            fontAscent = -8f,
        )

        assertEquals(30f, position.x, 0f)
        assertEquals(22f, position.y + -8f, 0f)
    }

    @Test fun letter_moves_down_two_dp_when_hint_is_visible() {
        val baseline = calculateHintedTextBaseline(
            centeredBaseline = 40f,
            keyBottom = 80f,
            densityFloat = 2f,
            fontDescent = 5f,
        )

        assertEquals(44f, baseline, 0f)
    }

    @Test fun long_press_on_a_hinted_key_types_the_hint() {
        val key = Key('q'.code, "q", numberHint = '1', symbolHint = '%')

        assertEquals(Key('%'.code, "%"), longPressTarget(key, hint = '%'))
        assertEquals(Key('1'.code, "1"), longPressTarget(key, hint = '1'))
    }

    @Test fun long_press_falls_back_to_the_key_long_press_code() {
        val comma = Key(','.code, ",", longPressCode = KeyCode.EMOJI)

        assertEquals(KeyCode.EMOJI, longPressTarget(comma, hint = null)?.code)
    }

    @Test fun plain_key_has_no_long_press_target() {
        assertEquals(null, longPressTarget(Key('a'.code, "a"), hint = null))
    }

    @Test fun hint_long_press_is_quicker_than_a_regular_long_press() {
        assertTrue(longPressDelayMs(hinted = true) < longPressDelayMs(hinted = false))
        assertTrue(longPressDelayMs(hinted = true) in 200L..280L)
    }

    private fun placementsBottom(metrics: KeyboardMetrics): Float {
        val placements = calculatePlacements(
            widthPx = 1080f,
            keyboard = QwertyKeyboardLayout.create(showDedicatedNumberRow = false),
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
            keyboard = NumericKeyboardLayout.create(),
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
