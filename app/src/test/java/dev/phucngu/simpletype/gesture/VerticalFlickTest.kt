package dev.phucngu.simpletype.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A vertical hint flick is any release whose whole path stayed inside a narrow
 * horizontal corridor around the touch-down point while travelling downward far
 * enough — regardless of speed, path length, or how many keys it crossed.
 * keyWidth=100, so the corridor is ±40px; minTravel mirrors the 22dp threshold.
 */
class VerticalFlickTest {

    private val keyWidth = 100f
    private val minTravel = 44f

    private fun flick(vararg points: Pair<Float, Float>): List<GesturePoint> =
        points.map { GesturePoint(it.first, it.second) }

    @Test
    fun `straight short flick is accepted`() {
        val path = flick(500f to 100f, 500f to 130f, 500f to 160f)
        assertTrue(isVerticalFlick(path, keyWidth, minTravel))
    }

    @Test
    fun `long slow swipe down across three rows is accepted`() {
        // From the top row through home row into the bottom row (~2.5 key heights).
        val path = (0..30).map { GesturePoint(500f + (it % 3) * 5f, 60f + it * 10f) }
        assertTrue(isVerticalFlick(path, keyWidth, minTravel))
    }

    @Test
    fun `wobble inside the corridor is accepted`() {
        val path = flick(500f to 100f, 470f to 140f, 530f to 180f, 505f to 220f)
        assertTrue(isVerticalFlick(path, keyWidth, minTravel))
    }

    @Test
    fun `path leaving the corridor is rejected`() {
        // Starts downward then veers a full key to the right (glide-like, e.g. "edit").
        val path = flick(500f to 100f, 500f to 200f, 600f to 210f, 700f to 215f)
        assertFalse(isVerticalFlick(path, keyWidth, minTravel))
    }

    @Test
    fun `horizontal swipe is rejected`() {
        val path = flick(500f to 100f, 600f to 105f, 700f to 110f)
        assertFalse(isVerticalFlick(path, keyWidth, minTravel))
    }

    @Test
    fun `travel below the threshold is rejected`() {
        val path = flick(500f to 100f, 502f to 120f, 500f to 140f)
        assertFalse(isVerticalFlick(path, keyWidth, minTravel))
    }

    @Test
    fun `upward swipe is rejected`() {
        val path = flick(500f to 300f, 500f to 200f, 500f to 100f)
        assertFalse(isVerticalFlick(path, keyWidth, minTravel))
    }

    @Test
    fun `degenerate paths are rejected`() {
        assertFalse(isVerticalFlick(emptyList(), keyWidth, minTravel))
        assertFalse(isVerticalFlick(flick(500f to 100f), keyWidth, minTravel))
    }
}
