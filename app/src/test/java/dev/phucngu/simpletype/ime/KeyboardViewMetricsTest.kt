package dev.phucngu.simpletype.ime

import android.view.View.MeasureSpec
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The keyboard view must grow taller when a larger row height is applied. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardViewMetricsTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun LatinKeyboardView.measuredHeightAt(widthPx: Int): Int {
        measure(
            MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        return measuredHeight
    }

    @Test fun larger_row_height_makes_a_taller_keyboard() {
        val v = LatinKeyboardView(ctx)
        v.applyMetrics(KeyboardMetrics.DEFAULT)
        val base = v.measuredHeightAt(1080)

        v.applyMetrics(KeyboardMetrics.of(KeyboardMetrics.ROW_HEIGHT_MAX, 4f, 4f))
        val taller = v.measuredHeightAt(1080)

        assertTrue("taller=$taller should exceed base=$base", taller > base)
    }
}
