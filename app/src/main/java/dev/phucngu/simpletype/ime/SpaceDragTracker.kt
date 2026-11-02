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
 * until [onHoldElapsed] enters cursor mode.
 *
 * In cursor mode the drag works like a trackpad:
 *  - **Acceleration.** Every [cursorStep] of horizontal drag moves one character while the finger
 *    moves slowly; as it speeds up from [SLOW_DP_PER_MS] to [FAST_DP_PER_MS] the same distance
 *    moves up to [MAX_GAIN] times as far. Slow is precise, fast is far.
 *  - **Lines.** Every [LINE_STEP_FACTOR] x [cursorStep] of vertical drag moves one line, without
 *    acceleration. The larger step keeps a sideways drag from wobbling onto another line.
 *  - **Axis lock.** While the drag clearly goes along one axis, movement across it counts for
 *    [CROSS_AXIS_DAMPING] as much, so a sideways drag drifting down doesn't jump a line.
 *  - **Lift-off.** A finger rolls a few dp as it lifts. [up] undoes characters moved in the last
 *    [LIFT_WINDOW_MS] when the finger had come to rest just before, so the cursor stays where the
 *    user put it.
 *
 * Times are in milliseconds and only their differences matter; positions are in px, converted to
 * dp with [density] for the speed thresholds.
 */
class SpaceDragTracker(
    private val swipeThreshold: Float,
    private val cursorStep: Float,
    private val density: Float = 1f,
) {
    private val lineStep = cursorStep * LINE_STEP_FACTOR
    private var startX = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L
    /** Smoothed finger velocity in px/ms. */
    private var vx = 0f
    private var vy = 0f
    /** Drag not yet turned into a whole character / line, in px. */
    private var accX = 0f
    private var accY = 0f
    private var swiped = false
    private var cursorMoved = false
    private var lastTickTime = Long.MIN_VALUE

    /** Recent cursor-mode moves, for lift-off compensation. */
    private class Sample(val time: Long, val steps: Int, val speedDpPerMs: Float)
    private val samples = ArrayDeque<Sample>()

    var cursorMode = false
        private set

    fun down(x: Float, y: Float = 0f, timeMs: Long = 0L) {
        startX = x
        lastX = x
        lastY = y
        lastTime = timeMs
        vx = 0f
        vy = 0f
        accX = 0f
        accY = 0f
        swiped = false
        cursorMoved = false
        cursorMode = false
        lastTickTime = Long.MIN_VALUE
        samples.clear()
    }

    /** Called when the hold timer fires. Returns true if cursor mode was entered. */
    fun onHoldElapsed(): Boolean {
        if (swiped || cursorMode) return false
        cursorMode = true
        // Holding means the finger was still; drift before this point isn't movement.
        vx = 0f
        vy = 0f
        accX = 0f
        accY = 0f
        return true
    }

    fun move(x: Float, y: Float = lastY, timeMs: Long = lastTime): SpaceAction? {
        val dx = x - lastX
        val dy = y - lastY
        val dt = timeMs - lastTime
        lastX = x
        lastY = y
        lastTime = timeMs
        if (dt > 0) {
            val alpha = dt / (dt + VELOCITY_SMOOTHING_MS)
            vx += alpha * (dx / dt - vx)
            vy += alpha * (dy / dt - vy)
        }

        if (cursorMode) return cursorMove(dx, dy, timeMs)
        if (!swiped && abs(x - startX) >= swipeThreshold) {
            swiped = true
            return SpaceAction.LanguageSwipe(if (x > startX) 1 else -1)
        }
        return null
    }

    private fun cursorMove(dx: Float, dy: Float, timeMs: Long): SpaceAction.CursorMove? {
        val speedX = abs(vx) / density
        val speedY = abs(vy) / density
        var effDx = dx
        var effDy = dy
        if (speedX > SLOW_DP_PER_MS && abs(vx) > AXIS_LOCK_RATIO * abs(vy)) effDy *= CROSS_AXIS_DAMPING
        else if (speedY > SLOW_DP_PER_MS && abs(vy) > AXIS_LOCK_RATIO * abs(vx)) effDx *= CROSS_AXIS_DAMPING

        accX += effDx * gain(speedX)
        accY += effDy
        val steps = (accX / cursorStep).toInt()
        val lines = (accY / lineStep).toInt()
        accX -= steps * cursorStep
        accY -= lines * lineStep

        samples.addLast(Sample(timeMs, steps, speedX))
        while (samples.size > 1 && timeMs - samples.first().time > SAMPLE_HISTORY_MS) samples.removeFirst()

        if (steps == 0 && lines == 0) return null
        cursorMoved = true
        return SpaceAction.CursorMove(steps, lines)
    }

    /** Distance multiplier for a finger moving at [speed] dp/ms: 1 when slow, [MAX_GAIN] when fast. */
    private fun gain(speed: Float): Float {
        val t = ((speed - SLOW_DP_PER_MS) / (FAST_DP_PER_MS - SLOW_DP_PER_MS)).coerceIn(0f, 1f)
        return 1f + (MAX_GAIN - 1f) * t * t * (3f - 2f * t)
    }

    /**
     * The finger lifted at [timeMs]. Returns the move that undoes the lift-off roll, if the last
     * few characters were moved by the finger rolling off after it had come to rest.
     */
    fun up(timeMs: Long): SpaceAction.CursorMove? {
        if (!cursorMode) return null
        val windowStart = timeMs - LIFT_WINDOW_MS
        val rolled = samples.filter { it.time >= windowStart }.sumOf { it.steps }
        if (rolled == 0 || abs(rolled) > LIFT_MAX_STEPS) return null
        val before = samples.lastOrNull { it.time < windowStart }
        val wasResting = before == null ||
            windowStart - before.time >= REST_GAP_MS ||
            before.speedDpPerMs < SLOW_DP_PER_MS
        return if (wasResting) SpaceAction.CursorMove(-rolled) else null
    }

    /** Whether a cursor tick vibration may play at [timeMs]; caps ticks so fast drags don't buzz. */
    fun hapticDue(timeMs: Long): Boolean {
        if (lastTickTime != Long.MIN_VALUE && timeMs - lastTickTime < HAPTIC_MIN_INTERVAL_MS) return false
        lastTickTime = timeMs
        return true
    }

    fun shouldTypeSpace(): Boolean = !swiped && !cursorMoved

    companion object {
        /** Vertical drag needed per line, as a multiple of the horizontal per-character step. */
        const val LINE_STEP_FACTOR = 3f
        /** Below this finger speed (150 dp/s) the drag is 1:1. */
        const val SLOW_DP_PER_MS = 0.15f
        /** At and above this finger speed (1200 dp/s) the drag moves [MAX_GAIN] times as far. */
        const val FAST_DP_PER_MS = 1.2f
        const val MAX_GAIN = 3f
        /** Time constant of the velocity smoothing. */
        const val VELOCITY_SMOOTHING_MS = 30f
        /** Along-axis velocity must exceed cross-axis velocity by this much to lock the axis. */
        const val AXIS_LOCK_RATIO = 2f
        const val CROSS_AXIS_DAMPING = 0.5f
        const val LIFT_WINDOW_MS = 80L
        /** Lift-off rolls larger than this are taken as intended movement. */
        const val LIFT_MAX_STEPS = 2
        /** No move event for this long before the lift window means the finger was resting. */
        const val REST_GAP_MS = 100L
        const val SAMPLE_HISTORY_MS = 400L
        const val HAPTIC_MIN_INTERVAL_MS = 25L
    }
}

/**
 * User-adjustable cursor travel speed for space-drag, as a multiplier on [BASE_STEP_DP]: at 2x
 * every 5dp of slow drag moves one character (faster drags accelerate on top of this). Stored in prefs under [LatinKeyboardView.PREF_CURSOR_SPEED].
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
