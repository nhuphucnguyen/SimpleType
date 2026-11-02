package dev.phucngu.simpletype.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pixel pointer the tracker feeds to [TrackpadCursor]: finger movement scaled by the cursor
 * speed setting, accelerated for fast drags, with the lift-off roll undone. density=1.
 */
class SpaceDragPointerTest {

    private fun cursorTracker(pointerGain: Float = 1f) =
        SpaceDragTracker(swipeThreshold = 56f, cursorStep = 20f, density = 1f, pointerGain = pointerGain).apply {
            down(500f, 300f, timeMs = 0L)
            assertTrue(onHoldElapsed())
        }

    private fun SpaceDragTracker.drag(dxPerFrame: Float, dyPerFrame: Float, frames: Int, t0: Long, x0: Float = 500f, y0: Float = 300f) {
        for (i in 1..frames) move(x0 + dxPerFrame * i, y0 + dyPerFrame * i, timeMs = t0 + 16L * i)
    }

    @Test
    fun `slow drag moves the pointer as far as the finger, in both axes`() {
        val t = cursorTracker()
        t.drag(2f, 1f, 50, t0 = 300L)
        val (dx, dy) = t.consumePointerDelta()
        assertEquals(100f, dx, 0.5f)
        assertEquals(50f, dy, 0.5f)
        assertEquals(0f to 0f, t.consumePointerDelta())
    }

    @Test
    fun `cursor speed setting scales the pointer`() {
        val t = cursorTracker(pointerGain = 1.5f)
        t.drag(2f, 0f, 50, t0 = 300L)
        assertEquals(150f, t.consumePointerDelta().first, 1f)
    }

    @Test
    fun `fast drag accelerates the pointer`() {
        val t = cursorTracker()
        t.drag(30f, 0f, 10, t0 = 300L) // ~1900 dp/s
        val dx = t.consumePointerDelta().first
        assertTrue("fast drag went $dx for 300px of finger", dx > 450f)
    }

    @Test
    fun `no pointer movement before cursor mode`() {
        val t = SpaceDragTracker(swipeThreshold = 56f, cursorStep = 20f, density = 1f).apply { down(500f, 300f, 0L) }
        t.move(510f, 300f, 16L)
        assertEquals(0f to 0f, t.consumePointerDelta())
    }

    @Test
    fun `lift-off roll after resting is undone`() {
        val t = cursorTracker()
        t.drag(2f, 0f, 20, t0 = 300L) // to x = 540
        t.consumePointerDelta()
        // Rest, then the finger rolls 12px as it lifts.
        t.move(546f, 300f, timeMs = 1000L)
        t.move(552f, 300f, timeMs = 1016L)
        t.up(timeMs = 1030L)
        assertEquals(0f, t.consumePointerDelta().first, 0.5f)
    }

    @Test
    fun `small drag in cursor mode does not type a space`() {
        val t = cursorTracker()
        t.drag(0f, 2f, 5, t0 = 300L) // 10px down: less than a line, no character steps
        assertFalse(t.shouldTypeSpace())
    }
}
