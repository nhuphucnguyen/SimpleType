package dev.phucngu.simpletype.ime

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** One visual line of the editor, in screen pixels. */
data class LineBand(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val midY get() = (top + bottom) / 2f
    val height get() = bottom - top
    operator fun contains(y: Float) = y in top..bottom
}

/**
 * What the editor reports about its caret, converted to screen pixels. [lines] are the visible
 * lines (may be empty where the editor doesn't report them); editor bounds are NaN when unknown.
 */
data class CaretGeometry(
    val selStart: Int,
    val selEnd: Int,
    /** Caret x; NaN when the editor reports none (it does so while text is selected). */
    val x: Float,
    val top: Float,
    val bottom: Float,
    val lines: List<LineBand> = emptyList(),
    val editorLeft: Float = Float.NaN,
    val editorRight: Float = Float.NaN,
)

/** The editor operations the trackpad cursor drives. All results come back as [CaretGeometry]. */
interface TrackpadEditor {
    /** Whether [hitTest] is available (Android 14+ editors supporting InsertGesture). */
    val canHitTest: Boolean
    fun setCursor(offset: Int)
    /** The offset [steps] user-perceived characters from [from], clamped to the text. */
    fun step(from: Int, steps: Int): Int
    /**
     * Asks the editor to put its caret at screen point ([x], [y]). Editors may instead select the
     * characters within [halfWidth] of it, which is collapsed. Answers with geometry, or with
     * [TrackpadCursor.onHitTestFailed].
     */
    fun hitTest(x: Float, y: Float, halfWidth: Float)
    /** Moves [lines] lines like the arrow keys. Returns false where that isn't possible. */
    fun moveLines(lines: Int): Boolean
}

/**
 * Space-drag cursor that works like the iOS keyboard's trackpad: the finger moves a pointer in
 * screen pixels over the text and the caret goes to the character under it.
 *
 *  - **Horizontal.** Each frame, the caret steps toward the pointer by the distance divided by the
 *    learned character width. Reports from the editor correct the estimate, so wide and narrow
 *    letters take as much finger travel as their on-screen width. A move is predicted until the
 *    editor reports it, so the caret keeps up between reports, and reports for older positions
 *    are ignored.
 *  - **Lines.** The pointer changes line when it leaves a line's band by a quarter line height.
 *    Then the editor is asked for the character under the pointer ([TrackpadEditor.hitTest]),
 *    falling back to arrow-key line moves.
 *  - **Edges.** The pointer is held to the line it is on, so coming back from past a line's end
 *    responds at once. A caret at the end of a soft-wrapped line is shown at the start of the
 *    next; that isn't taken as a line change. Pushing above or below the visible lines scrolls
 *    one line per half line height.
 */
class TrackpadCursor(private val editor: TrackpadEditor) {
    /** True once the editor has reported geometry; until then the caller uses character steps. */
    var ready = false
        private set

    private var caretOffset = 0
    private var caretX = 0f
    private var caretMidY = 0f
    private var lineHeight = 1f
    private var bands: List<LineBand> = emptyList()
    private var reportedBands = false
    private var editorLeft = Float.NaN
    private var editorRight = Float.NaN

    private var px = 0f
    private var py = 0f
    /** Middle of the line the pointer is on; null until the first report. */
    private var pointerLineY = 0f
    private var charWidth = 0f

    /** Offset of a setCursor not yet reported back, and where we expect the caret to be. */
    private var pending: Int? = null
    private var pendingAt = 0L
    private var predictedX = 0f
    private var hitPending = false
    /** Set once the editor fails or ignores a hit test; it won't do better next time. */
    private var hitTestBroken = false
    private var hitAt = 0L
    private var hitTarget: LineBand? = null
    private var verticalPending = false
    private var verticalFrom = 0
    private var verticalAt = 0L
    private var lastDir = 0
    /** +1 while the caret sits at the end of a soft-wrapped line, shown on the next line. */
    private var wrapBlock = 0
    /** Direction reversals without pointer movement, to stop a caret oscillating. */
    private var reversals = 0

    /** A report whose offsets didn't match the move in flight, kept for [onSelection]. */
    private var held: CaretGeometry? = null
    /** Moves sent but not yet reported, oldest first; the last one is [pending]. */
    private val inFlight = ArrayDeque<Int>()
    /** Selection numbers known to be out of date: Chrome repeats them after a hit test. */
    private var staleSel: Pair<Int, Int>? = null

    private var resolving = false
    private var dirty = false

    /** A hit test was sent whose selection hasn't been collapsed yet. */
    val awaitingHit get() = hitPending

    /** Where the pointer is, in screen pixels, for drawing the floating cursor. */
    val pointerX get() = px
    /** The pointer's vertical position, kept within the visible lines. */
    val pointerY: Float
        get() {
            if (!reportedBands || bands.isEmpty()) return py
            return py.coerceIn(bands.first().top + lineHeight / 2f, bands.last().bottom - lineHeight / 2f)
        }
    val caretHeight get() = lineHeight
    /**
     * True while the pointer is away from the caret (past a line's end, or between lines), when
     * the floating cursor is worth drawing. Over the text the caret itself follows the finger, so
     * a second cursor next to it is just noise. Hysteresis keeps it from flickering at the edge.
     */
    val pointerDetached: Boolean
        get() {
            val shownX = if (pending != null) predictedX else caretX
            val dx = abs(px - shownX) / max(charWidth, 1f)
            val dy = abs(pointerY - caretMidY) / lineHeight
            detached = if (detached) dx > ATTACH_CHARS || dy > ATTACH_LINES
            else dx > DETACH_CHARS || dy > DETACH_LINES
            return detached
        }
    private var detached = false

    fun onGeometry(g: CaretGeometry, timeMs: Long) {
        lineHeight = max(g.bottom - g.top, 1f)
        editorLeft = g.editorLeft
        editorRight = g.editorRight
        reportedBands = g.lines.isNotEmpty()
        bands = if (reportedBands) mergeLines(g.lines) else synthesizeLines(g)

        if (hitPending) {
            when {
                // The hit test selected the character(s) under the pointer: collapse to a caret.
                g.selStart != g.selEnd && !isStale(g) -> {
                    hitPending = false
                    staleSel = g.selStart to g.selEnd
                    setCursor(g.selStart, g.x, timeMs)
                }
                // Some editors place a caret instead of selecting.
                g.selStart == g.selEnd && g.selEnd != caretOffset && !isStale(g) && !g.x.isNaN() -> {
                    hitPending = false
                    staleSel = null
                    accept(g, timeMs)
                }
                // The old caret's numbers, possibly with the new position (Chrome): wait for
                // the selection.
                else -> held = g
            }
            return
        }
        // Editors report no caret position while text is selected; nothing to track from that.
        if (g.x.isNaN()) return
        val offset = g.selEnd
        val p = pending
        if (p != null && isStale(g)) {
            held = g
            return
        }
        if (verticalPending && offset == verticalFrom && timeMs - verticalAt < STALE_MS) return
        when {
            p == null || offset == p || offset in inFlight || timeMs - pendingAt >= STALE_MS -> accept(g, timeMs)
            // A position we didn't ask for while a move is in flight: wait for the answer.
            else -> return
        }
    }

    /** Takes [g] as where the caret is now. */
    private fun accept(g: CaretGeometry, timeMs: Long) {
        held = null
        val offset = g.selEnd
        val midY = (g.top + g.bottom) / 2f
        if (ready && offset != caretOffset && abs(midY - caretMidY) < lineHeight / 2f) {
            val moved = abs(offset - caretOffset)
            if (moved <= LEARN_MAX_CHARS) {
                val w = abs(g.x - caretX) / moved
                if (w > 0f) charWidth = charWidth * (1f - LEARN_RATE) + w * LEARN_RATE
            }
        }
        caretOffset = offset
        caretX = g.x
        caretMidY = midY
        val p = pending
        val i = inFlight.indexOf(offset)
        if (p != null && offset != p && i >= 0) {
            // A late report for an earlier move (EditText, Compose): newer moves are still in
            // flight, predicted from this position.
            repeat(i + 1) { inFlight.removeFirst() }
            predictedX = g.x + (p - offset) * charWidth
        } else {
            pending = null
            inFlight.clear()
        }
        if (offset != verticalFrom) verticalPending = false
        if (!ready) {
            ready = true
            px = g.x
            py = midY
            pointerLineY = midY
            charWidth = lineHeight * INITIAL_CHAR_WIDTH
        }
        resolve(timeMs)
    }

    private fun isStale(g: CaretGeometry) = staleSel == (g.selStart to g.selEnd)

    /**
     * The editor's selection changed (InputMethodService.onUpdateSelection). After a hit test,
     * Chrome sends a report with the new caret position but the old selection numbers, and only
     * then updates the selection; the held report is applied now with the right numbers.
     */
    fun onSelection(start: Int, end: Int, timeMs: Long) {
        val h = held ?: return
        if (staleSel == (start to end)) return
        held = null
        staleSel = null
        onGeometry(h.copy(selStart = start, selEnd = end), timeMs)
    }

    fun onPointerDelta(dx: Float, dy: Float, timeMs: Long) {
        if (!ready) return
        if (dx == 0f && dy == 0f) {
            resolve(timeMs)
            return
        }
        px += dx
        py += dy
        val left = if (editorLeft.isNaN()) bands.minOfOrNull { it.left } else editorLeft
        val right = if (editorRight.isNaN()) bands.maxOfOrNull { it.right } else editorRight
        if (left != null && right != null && right >= left) px = px.coerceIn(left, right)
        reversals = 0
        resolve(timeMs)
    }

    fun onHitTestFailed(timeMs: Long) {
        if (!hitPending) return
        hitPending = false
        hitTestBroken = true
        val target = hitTarget ?: return
        moveLinesTo(target, timeMs)
    }

    private fun resolve(timeMs: Long) {
        if (resolving) {
            dirty = true
            return
        }
        resolving = true
        try {
            var rounds = 0
            do {
                dirty = false
                resolveOnce(timeMs)
            } while (dirty && ++rounds < MAX_ROUNDS)
        } finally {
            resolving = false
        }
    }

    private fun resolveOnce(timeMs: Long) {
        if (!ready) return
        if (hitPending) {
            // Editors may accept the hit test and never report a selection (seen in Chrome).
            if (timeMs - hitAt < HIT_TIMEOUT_MS) return
            hitPending = false
            hitTestBroken = true
            hitTarget?.let { moveLinesTo(it, timeMs) }
            return
        }
        if (verticalPending && timeMs - verticalAt < STALE_MS) return
        verticalPending = false
        if (bands.isEmpty()) return

        // Pushing beyond the visible lines scrolls a line at a time.
        if (reportedBands) {
            val first = bands.first()
            val last = bands.last()
            val overflow = when {
                py < first.top - lineHeight * SCROLL_PUSH -> -1
                py > last.bottom + lineHeight * SCROLL_PUSH -> 1
                else -> 0
            }
            if (overflow != 0) {
                py = (if (overflow < 0) first else last).midY
                pointerLineY = py
                verticalFrom = caretOffset
                verticalAt = timeMs
                verticalPending = true
                if (!editor.moveLines(overflow)) verticalPending = false
                return
            }
        }

        val current = bandNear(pointerLineY)
        val hysteresis = lineHeight * LINE_HYSTERESIS
        val pointerBand = if (py in (current.top - hysteresis)..(current.bottom + hysteresis)) current else bandNear(py)
        // The pointer stays where the finger put it (past a line's end too, so the column survives
        // a short line); the caret goes to the nearest position on the line.
        val targetX = px.coerceIn(pointerBand.left, max(pointerBand.left, pointerBand.right))
        val caretBand = bandNear(caretMidY)

        if (pointerBand != current) {
            pointerLineY = pointerBand.midY
            wrapBlock = 0
            lastDir = 0
            if (caretBand != pointerBand) {
                changeLine(pointerBand, timeMs)
                return
            }
        } else if (caretBand != pointerBand && pending == null) {
            val below = bands.indexOf(caretBand) == bands.indexOf(pointerBand) + 1
            if (lastDir > 0 && below) {
                wrapBlock = 1
            } else if (wrapBlock == 0) {
                changeLine(pointerBand, timeMs)
                return
            }
        }

        val atWrap = wrapBlock > 0 && caretBand != pointerBand && pending == null
        val fromX = when {
            pending != null -> predictedX
            atWrap -> pointerBand.right
            else -> caretX
        }
        val n = (targetX - fromX) / charWidth
        var steps = (sign(n) * floor(abs(n) + 0.5f - STEP_HYSTERESIS)).toInt()
        if (atWrap && steps > 0) steps = 0
        if (steps == 0) return

        val dir = sign(steps.toFloat()).toInt()
        if (lastDir != 0 && dir != lastDir) {
            if (++reversals > MAX_REVERSALS) return
        }
        val from = pending ?: caretOffset
        val to = editor.step(from, steps)
        if (to == from) return
        lastDir = dir
        if (dir < 0) wrapBlock = 0
        setCursor(to, fromX + steps * charWidth, timeMs)
    }

    private fun setCursor(offset: Int, expectedX: Float, timeMs: Long) {
        held = null
        inFlight.addLast(offset)
        pending = offset
        pendingAt = timeMs
        predictedX = expectedX
        editor.setCursor(offset)
    }

    private fun changeLine(target: LineBand, timeMs: Long) {
        pending = null
        inFlight.clear()
        held = null
        if (editor.canHitTest && !hitTestBroken) {
            hitPending = true
            hitAt = timeMs
            staleSel = caretOffset to caretOffset
            hitTarget = target
            val half = max(charWidth, lineHeight) / 2f
            val right = max(target.left, target.right - charWidth / 2f)
            val x = px.coerceIn(min(target.left + charWidth / 2f, right), right)
            editor.hitTest(x, target.midY, half)
        } else {
            moveLinesTo(target, timeMs)
        }
    }

    private fun moveLinesTo(target: LineBand, timeMs: Long) {
        val from = bands.indexOf(bandNear(caretMidY))
        val to = bands.indexOf(target)
        val lines = if (from >= 0 && to >= 0) to - from else ((target.midY - caretMidY) / lineHeight).toInt()
        if (lines == 0) return
        verticalFrom = caretOffset
        verticalAt = timeMs
        verticalPending = true
        if (!editor.moveLines(lines)) {
            verticalPending = false
            py = caretMidY
            pointerLineY = caretMidY
        }
    }

    private fun bandNear(y: Float): LineBand =
        bands.firstOrNull { y in it } ?: bands.minBy { abs(it.midY - y) }

    /** Without reported lines, assume equal-height lines above and below the caret. */
    private fun synthesizeLines(g: CaretGeometry): List<LineBand> {
        val left = if (g.editorLeft.isNaN()) -UNBOUNDED else g.editorLeft
        val right = if (g.editorRight.isNaN()) UNBOUNDED else g.editorRight
        return (-SYNTH_LINES..SYNTH_LINES).map {
            LineBand(left, g.top + it * lineHeight, right, g.bottom + it * lineHeight)
        }
    }

    companion object {
        /** Floating cursor shows beyond this many characters or lines from the caret, hides within the next. */
        private const val DETACH_CHARS = 1.2f
        private const val ATTACH_CHARS = 0.8f
        private const val DETACH_LINES = 0.8f
        private const val ATTACH_LINES = 0.55f
        /** Ignore reports of other positions for this long after a move. */
        const val STALE_MS = 250L
        /** How long to wait for a hit test's selection before moving by lines instead. */
        const val HIT_TIMEOUT_MS = 150L
        /** Starting guess for the character width, as a fraction of the line height. */
        const val INITIAL_CHAR_WIDTH = 0.45f
        const val LEARN_RATE = 0.7f
        const val LEARN_MAX_CHARS = 8
        /** A step happens once the pointer is this far past the midpoint between two positions. */
        const val STEP_HYSTERESIS = 0.15f
        /** Fraction of a line height the pointer must go past a line's edge to change line. */
        const val LINE_HYSTERESIS = 0.25f
        const val SCROLL_PUSH = 0.5f
        const val MAX_REVERSALS = 2
        const val MAX_ROUNDS = 20
        const val SYNTH_LINES = 40
        const val UNBOUNDED = 1e6f

        /** Merges line rects that overlap vertically (text runs of one line) into one band per line. */
        fun mergeLines(rects: List<LineBand>): List<LineBand> {
            val out = mutableListOf<LineBand>()
            for (r in rects.sortedBy { it.top }) {
                val last = out.lastOrNull()
                val overlap = if (last == null) 0f else min(last.bottom, r.bottom) - max(last.top, r.top)
                if (last != null && overlap > min(last.height, r.height) / 2f) {
                    out[out.size - 1] = LineBand(
                        min(last.left, r.left), min(last.top, r.top), max(last.right, r.right), max(last.bottom, r.bottom)
                    )
                } else {
                    out += r
                }
            }
            return out
        }
    }
}
