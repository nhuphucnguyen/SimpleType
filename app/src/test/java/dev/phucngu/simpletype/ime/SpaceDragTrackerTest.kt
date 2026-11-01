package dev.phucngu.simpletype.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The space key carries two horizontal gestures. Time separates them: a flick that crosses the
 * swipe threshold before the hold timer fires toggles the language; resting on space until the
 * timer fires enters cursor mode, after which horizontal drag only moves the cursor.
 * swipeThreshold=56px, cursorStep=20px, so lineStep=60px (3x).
 */
class SpaceDragTrackerTest {

    private fun tracker() = SpaceDragTracker(swipeThreshold = 56f, cursorStep = 20f).apply { down(500f) }

    @Test
    fun `quick flick toggles language once`() {
        val t = tracker()
        assertNull(t.move(530f))
        assertEquals(SpaceAction.LanguageSwipe(1), t.move(560f))
        assertNull(t.move(620f))
        assertFalse(t.onHoldElapsed())
        assertFalse(t.shouldTypeSpace())
    }

    @Test
    fun `left flick toggles with negative direction`() {
        val t = tracker()
        assertEquals(SpaceAction.LanguageSwipe(-1), t.move(440f))
    }

    @Test
    fun `hold then drag moves cursor instead of toggling language`() {
        val t = tracker()
        assertTrue(t.onHoldElapsed())
        assertTrue(t.cursorMode)
        assertNull(t.move(515f))
        assertEquals(SpaceAction.CursorMove(1), t.move(520f))
        assertEquals(SpaceAction.CursorMove(2), t.move(565f))
        assertEquals(SpaceAction.CursorMove(-4), t.move(480f))
        assertFalse(t.shouldTypeSpace())
    }

    @Test
    fun `drag far past swipe threshold after hold never toggles language`() {
        val t = tracker()
        t.onHoldElapsed()
        val actions = (501..900 step 7).mapNotNull { t.move(it.toFloat()) }
        assertTrue(actions.all { it is SpaceAction.CursorMove })
        assertEquals((900 - 500) / 20, actions.sumOf { (it as SpaceAction.CursorMove).steps })
    }

    @Test
    fun `small jitter before hold is not counted as cursor movement`() {
        val t = tracker()
        assertNull(t.move(515f))
        assertTrue(t.onHoldElapsed())
        assertNull(t.move(530f)) // only 15px from where cursor mode began
        assertEquals(SpaceAction.CursorMove(1), t.move(535f))
    }

    @Test
    fun `plain tap types space`() {
        val t = tracker()
        assertTrue(t.shouldTypeSpace())
    }

    @Test
    fun `hold and release without dragging still types space`() {
        val t = tracker()
        t.onHoldElapsed()
        assertNull(t.move(505f))
        assertTrue(t.shouldTypeSpace())
    }

    @Test
    fun `down resets previous gesture`() {
        val t = tracker()
        t.onHoldElapsed()
        t.move(600f)
        t.down(100f)
        assertFalse(t.cursorMode)
        assertEquals(SpaceAction.LanguageSwipe(1), t.move(160f))
    }

    @Test
    fun `hold then drag up moves cursor up a line`() {
        val t = tracker()
        t.down(500f, 300f)
        assertTrue(t.onHoldElapsed())
        assertNull(t.move(500f, 250f)) // under one line step
        assertEquals(SpaceAction.CursorMove(0, -1), t.move(500f, 240f))
        assertEquals(SpaceAction.CursorMove(0, -2), t.move(500f, 110f))
        assertEquals(SpaceAction.CursorMove(0, 1), t.move(500f, 180f))
        assertFalse(t.shouldTypeSpace())
    }

    @Test
    fun `diagonal drag moves both axes`() {
        val t = tracker()
        t.down(500f, 300f)
        t.onHoldElapsed()
        assertEquals(SpaceAction.CursorMove(2, -1), t.move(540f, 240f))
    }

    @Test
    fun `mostly horizontal drag with vertical wobble stays on the line`() {
        val t = tracker()
        t.down(500f, 300f)
        t.onHoldElapsed()
        val actions = (501..900 step 7).mapNotNull { t.move(it.toFloat(), 300f + (it % 3) * 15f) }
        assertTrue(actions.all { it is SpaceAction.CursorMove && it.lines == 0 })
    }

    @Test
    fun `vertical drag before hold is ignored`() {
        val t = tracker()
        t.down(500f, 300f)
        assertNull(t.move(500f, 200f))
        assertTrue(t.onHoldElapsed())
        assertNull(t.move(500f, 160f)) // only 40px from where cursor mode began
        assertEquals(SpaceAction.CursorMove(0, -1), t.move(500f, 140f))
    }
}
