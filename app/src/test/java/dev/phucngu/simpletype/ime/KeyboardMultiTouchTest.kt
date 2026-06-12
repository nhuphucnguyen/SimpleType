package dev.phucngu.simpletype.ime

import android.graphics.RectF
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Multi-touch behaviour of [LatinKeyboardView]: holding Shift with one finger and pressing
 * another key with a second finger. The view must treat Shift as a held modifier (firing
 * [LatinKeyboardView.Listener.onShiftHold]) rather than locking on the single primary pointer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardMultiTouchTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class RecordingListener : LatinKeyboardView.Listener {
        val events = mutableListOf<String>()
        override fun onKey(key: Key) { events += "key:${key.code}" }
        override fun onKeyRepeat(key: Key) { events += "repeat:${key.code}" }
        override fun onSpaceSwipe(direction: Int) { events += "swipe:$direction" }
        override fun onShiftHold(active: Boolean) { events += "hold:$active" }
    }

    private fun newView(): LatinKeyboardView {
        val v = LatinKeyboardView(ctx)
        v.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v
    }

    /** Center of the placement for [code], read via reflection (placements are private). */
    private fun centerOf(v: LatinKeyboardView, code: Int): Pair<Float, Float> {
        val field = LatinKeyboardView::class.java.getDeclaredField("placements").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val placements = field.get(v) as List<Any>
        for (p in placements) {
            val key = p.javaClass.getDeclaredField("key").apply { isAccessible = true }.get(p) as Key
            if (key.code == code) {
                val rect = p.javaClass.getDeclaredField("rect").apply { isAccessible = true }.get(p) as RectF
                return rect.centerX() to rect.centerY()
            }
        }
        error("no placement for code $code")
    }

    private fun event(v: LatinKeyboardView, action: Int, ids: IntArray, pts: Array<Pair<Float, Float>>): MotionEvent {
        val props = Array(ids.size) { MotionEvent.PointerProperties().apply { id = ids[it]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(pts.size) { MotionEvent.PointerCoords().apply { x = pts[it].first; y = pts[it].second; pressure = 1f; size = 1f } }
        val ev = MotionEvent.obtain(0, 0, action, ids.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        v.onTouchEvent(ev)
        return ev
    }

    @Test
    fun hold_shift_then_press_delete_fires_word_delete_without_toggling() {
        val v = newView()
        val listener = RecordingListener()
        v.listener = listener

        val shift = centerOf(v, KeyCode.SHIFT)
        val del = centerOf(v, KeyCode.DELETE)
        val pdMask = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val puMask = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

        // Finger 0 holds Shift; finger 1 presses Delete; lift Delete; lift Shift.
        event(v, MotionEvent.ACTION_DOWN, intArrayOf(0), arrayOf(shift))
        event(v, pdMask, intArrayOf(0, 1), arrayOf(shift, del))
        event(v, puMask, intArrayOf(0, 1), arrayOf(shift, del))
        event(v, MotionEvent.ACTION_UP, intArrayOf(0), arrayOf(shift))

        // Shift activated as a modifier, Delete fired while held, then Shift released.
        assertTrue("expected hold:true, got ${listener.events}", listener.events.contains("hold:true"))
        assertTrue("expected Delete to fire, got ${listener.events}",
            listener.events.contains("repeat:${KeyCode.DELETE}") || listener.events.contains("key:${KeyCode.DELETE}"))
        assertEquals("Shift must not toggle when used as a modifier; got ${listener.events}",
            0, listener.events.count { it == "key:${KeyCode.SHIFT}" })
        assertTrue("expected hold:false on release, got ${listener.events}", listener.events.contains("hold:false"))
    }

    @Test
    fun plain_shift_tap_still_toggles() {
        val v = newView()
        val listener = RecordingListener()
        v.listener = listener
        val shift = centerOf(v, KeyCode.SHIFT)

        event(v, MotionEvent.ACTION_DOWN, intArrayOf(0), arrayOf(shift))
        event(v, MotionEvent.ACTION_UP, intArrayOf(0), arrayOf(shift))

        assertEquals(listOf("key:${KeyCode.SHIFT}"), listener.events)
    }
}
