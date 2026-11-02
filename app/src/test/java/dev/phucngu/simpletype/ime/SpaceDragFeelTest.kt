package dev.phucngu.simpletype.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How space-drag cursor movement feels: speed-based acceleration (slow = precise, fast = far),
 * undoing the jump a finger makes as it lifts off, axis locking, and haptic throttling.
 * density=1, so px == dp; cursorStep=20px, lineStep=60px.
 */
class SpaceDragFeelTest {

    private val frameMs = 16L

    private fun cursorTracker(x: Float = 500f, y: Float = 300f): SpaceDragTracker =
        SpaceDragTracker(swipeThreshold = 56f, cursorStep = 20f, density = 1f).apply {
            down(x, y, timeMs = 0L)
            assertTrue(onHoldElapsed())
        }

    /** Drags at a constant [pxPerFrame] (plus [dyPerFrame]) for [frames] frames from [t0]. */
    private fun SpaceDragTracker.drag(
        fromX: Float, pxPerFrame: Float, frames: Int, t0: Long,
        fromY: Float = 300f, dyPerFrame: Float = 0f,
    ): List<SpaceAction.CursorMove> = (1..frames).mapNotNull {
        move(fromX + pxPerFrame * it, fromY + dyPerFrame * it, timeMs = t0 + frameMs * it) as SpaceAction.CursorMove?
    }

    private fun List<SpaceAction.CursorMove>.steps() = sumOf { it.steps }
    private fun List<SpaceAction.CursorMove>.lines() = sumOf { it.lines }

    @Test
    fun `slow drag moves one character per base step`() {
        val t = cursorTracker()
        // 2px per frame = 125 px/s: precise, no acceleration. 200px / 20px = 10 characters.
        assertEquals(10, t.drag(500f, 2f, 100, t0 = 300L).steps())
    }

    @Test
    fun `fast drag covers more characters than the same distance dragged slowly`() {
        val t = cursorTracker()
        // 30px per frame ~ 1900 px/s over 300px: 15 characters unaccelerated.
        val steps = t.drag(500f, 30f, 10, t0 = 300L).steps()
        assertTrue("fast drag moved only $steps", steps >= 30)
    }

    @Test
    fun `slowing down after a fast drag is precise again`() {
        val t = cursorTracker()
        t.drag(500f, 30f, 10, t0 = 300L)
        // Pause, then crawl 100px: back to about 5 characters.
        val slow = t.drag(800f, 2f, 50, t0 = 300L + 10 * frameMs + 200L).steps()
        assertTrue("slow crawl moved $slow", slow in 4..6)
    }

    @Test
    fun `fast leftward drag accelerates too`() {
        val t = cursorTracker()
        val steps = t.drag(500f, -30f, 10, t0 = 300L).steps()
        assertTrue("fast left drag moved only $steps", steps <= -30)
    }

    @Test
    fun `finger roll while lifting after resting is undone`() {
        val t = cursorTracker()
        val placed = t.drag(500f, 2f, 50, t0 = 300L).steps() // slow placement: 5 chars
        assertEquals(5, placed)
        // Rest for 300ms (no events), then the finger rolls 25px in the last 40ms before lifting.
        val restEnd = 300L + 50 * frameMs + 300L
        val roll = listOfNotNull(
            t.move(612f, 300f, timeMs = restEnd + 16L),
            t.move(625f, 300f, timeMs = restEnd + 32L),
        ).map { it as SpaceAction.CursorMove }.steps()
        assertTrue("roll should have moved the cursor", roll != 0)
        assertEquals(SpaceAction.CursorMove(-roll), t.up(timeMs = restEnd + 40L))
    }

    @Test
    fun `lifting at the end of a deliberate drag keeps the movement`() {
        val t = cursorTracker()
        t.drag(500f, 8f, 30, t0 = 300L) // a steady 500 px/s drag
        assertNull(t.up(timeMs = 300L + 30 * frameMs + 4L))
    }

    @Test
    fun `lifting after a fast drag keeps the movement`() {
        val t = cursorTracker()
        t.drag(500f, 30f, 10, t0 = 300L)
        assertNull(t.up(timeMs = 300L + 10 * frameMs + 4L))
    }

    @Test
    fun `lift without cursor mode does nothing`() {
        val t = SpaceDragTracker(swipeThreshold = 56f, cursorStep = 20f, density = 1f).apply { down(500f, 300f, 0L) }
        assertNull(t.up(timeMs = 50L))
    }

    @Test
    fun `vertical wobble during a sideways drag does not change line`() {
        val t = cursorTracker()
        // 400px sideways at ~1000 px/s while drifting 80px down: more than a line step.
        val moves = t.drag(500f, 16f, 25, t0 = 300L, dyPerFrame = 3.2f)
        assertEquals(0, moves.lines())
        assertTrue(moves.steps() > 0)
    }

    @Test
    fun `horizontal wobble during an up-down drag does not move along the line`() {
        val t = cursorTracker()
        // 240px up at ~1000 px/s while drifting 30px sideways: more than a character step.
        val moves = t.drag(500f, 2f, 15, t0 = 300L, dyPerFrame = -16f)
        assertEquals(0, moves.steps())
        assertEquals(-4, moves.lines())
    }

    @Test
    fun `haptic ticks are throttled during fast movement`() {
        val t = cursorTracker()
        assertTrue(t.hapticDue(timeMs = 1000L))
        assertFalse(t.hapticDue(timeMs = 1010L))
        assertTrue(t.hapticDue(timeMs = 1030L))
    }
}
