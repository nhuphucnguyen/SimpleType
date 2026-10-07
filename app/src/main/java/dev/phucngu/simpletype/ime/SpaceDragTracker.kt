package dev.phucngu.simpletype.ime

import kotlin.math.abs

sealed interface SpaceAction {
    /** Toggle input language; [direction] is +1 for right, -1 for left. */
    data class LanguageSwipe(val direction: Int) : SpaceAction
    /** Move the cursor by [steps] characters; negative moves left. */
    data class CursorMove(val steps: Int) : SpaceAction
}

/**
 * Disambiguates the two horizontal gestures on the space key by time: a flick that crosses
 * [swipeThreshold] before the hold timer fires is a language swipe, while resting on the key
 * until [onHoldElapsed] enters cursor mode, where every [cursorStep] of drag moves one character.
 */
class SpaceDragTracker(private val swipeThreshold: Float, private val cursorStep: Float) {
    private var startX = 0f
    private var anchorX = 0f
    private var swiped = false
    private var cursorMoved = false

    var cursorMode = false
        private set

    fun down(x: Float) {
        startX = x
        anchorX = x
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

    fun move(x: Float): SpaceAction? {
        if (cursorMode) {
            val steps = ((x - anchorX) / cursorStep).toInt()
            if (steps == 0) return null
            anchorX += steps * cursorStep
            cursorMoved = true
            return SpaceAction.CursorMove(steps)
        }
        if (!swiped && abs(x - startX) >= swipeThreshold) {
            swiped = true
            return SpaceAction.LanguageSwipe(if (x > startX) 1 else -1)
        }
        // Track the latest position so drift before cursor mode isn't counted as movement.
        anchorX = x
        return null
    }

    fun shouldTypeSpace(): Boolean = !swiped && !cursorMoved
}
