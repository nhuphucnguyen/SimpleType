package dev.phucngu.simpletype.ime

import android.graphics.RectF
import android.view.View.MeasureSpec
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every letter key must be the same width across rows (the asdf row is indented, Gboard-style). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardKeySizeTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun laidOutView(widthPx: Int): LatinKeyboardView {
        val v = LatinKeyboardView(ctx)
        v.measure(
            MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v
    }

    private fun widthOf(v: LatinKeyboardView, c: Char): Float {
        val field = LatinKeyboardView::class.java.getDeclaredField("placements").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val placements = field.get(v) as List<Any>
        for (p in placements) {
            val key = p.javaClass.getDeclaredField("key").apply { isAccessible = true }.get(p) as Key
            if (key.code == c.code) {
                val rect = p.javaClass.getDeclaredField("rect").apply { isAccessible = true }.get(p) as RectF
                return rect.width()
            }
        }
        error("no placement for '$c'")
    }

    @Test fun letter_keys_are_equal_width_across_rows() {
        val v = laidOutView(1080)
        val q = widthOf(v, 'q') // row 1 (10 keys)
        val a = widthOf(v, 'a') // row 2 (9 keys, must be indented to match)
        val z = widthOf(v, 'z') // row 3 (between shift & delete)
        assertEquals("row2 letter width should match row1", q, a, 0.5f)
        assertEquals("row3 letter width should match row1", q, z, 0.5f)
    }
}
