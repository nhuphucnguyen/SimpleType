package dev.phucngu.simpletype.ime

import kotlin.math.abs

sealed interface SpaceAction {
    /** Toggle input language; [direction] is +1 for right, -1 for left. */
    data class LanguageSwipe(val direction: Int) : SpaceAction
    /**
     * Move the cursor by [steps] characters (negative = left) and [lines] lines (negative = up).
     */
    data class CursorMove(val steps: Int, val lines: Int = 0) : SpaceAction
}

/**
 * Disambiguates the two horizontal gestures on the space key by time: a flick that crosses
 * [swipeThreshold] before the hold timer fires is a language swipe, while resting on the key
 * until [onHoldElapsed] enters cursor mode, where every [cursorStep] of horizontal drag moves one
 * character and every [LINE_STEP_FACTOR] x [cursorStep] of vertical drag moves one line. The larger
 * vertical step keeps a sideways drag from wobbling onto another line.
 */
class SpaceDragTracker(private val swipeThreshold: Float, private val cursorStep: Float) {
    private val lineStep = cursorStep * LINE_STEP_FACTOR
    private var startX = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var swiped = false
    private var cursorMoved = false

    var cursorMode = false
        private set

    fun down(x: Float, y: Float = 0f) {
        startX = x
        anchorX = x
        anchorY = y
        swiped = false
        cursorMoved = false
        cursorMode = false
    }

    /** Called when the hold timer fires. Returns true if cursor mode was entered. */
    fun onHoldElapsed(): Boolean {
        if (swiped || cursorMode) return false
        cursorMode = true
        return true
    }

    fun move(x: Float, y: Float = anchorY): SpaceAction? {
        if (cursorMode) {
            val steps = ((x - anchorX) / cursorStep).toInt()
            val lines = ((y - anchorY) / lineStep).toInt()
            if (steps == 0 && lines == 0) return null
            anchorX += steps * cursorStep
            anchorY += lines * lineStep
            cursorMoved = true
            return SpaceAction.CursorMove(steps, lines)
        }
        if (!swiped && abs(x - startX) >= swipeThreshold) {
            swiped = true
            return SpaceAction.LanguageSwipe(if (x > startX) 1 else -1)
        }
        // Track the latest position so drift before cursor mode isn't counted as movement.
        anchorX = x
        anchorY = y
        return null
    }

    fun shouldTypeSpace(): Boolean = !swiped && !cursorMoved

    companion object {
        /** Vertical drag needed per line, as a multiple of the horizontal per-character step. */
        const val LINE_STEP_FACTOR = 3f
    }
}

/**
 * User-adjustable cursor travel speed for space-drag, as a multiplier on [BASE_STEP_DP]: at 2x
 * every 5dp of drag moves one character. Stored in prefs under [LatinKeyboardView.PREF_CURSOR_SPEED].
 */
object CursorSpeed {
    const val BASE_STEP_DP = 10f
    const val MIN = 0.5f
    const val MAX = 3f
    const val DEFAULT = 1f
    /** Slider increment; the range MIN..MAX splits into whole multiples of this. */
    const val INCREMENT = 0.25f

    fun stepDp(speed: Float): Float = BASE_STEP_DP / speed.coerceIn(MIN, MAX)
}
