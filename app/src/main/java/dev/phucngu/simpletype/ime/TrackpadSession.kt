package dev.phucngu.simpletype.ime

import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InsertGesture
import android.view.inputmethod.InputConnection
import androidx.annotation.RequiresApi
import dev.phucngu.simpletype.text.moveByGraphemes
import java.util.concurrent.Executor

/**
 * Connects a [TrackpadCursor] to the focused editor for one space-drag: asks the editor to report
 * its caret geometry, converts the reports to screen pixels, and carries out the cursor's moves.
 *
 * [text] is the field's text when it could be read, for whole-character steps; [lineMover] sends
 * arrow-key line moves and returns false where that isn't possible (single-line fields).
 */
class TrackpadSession(
    private val ic: InputConnection,
    info: EditorInfo?,
    private val text: CharSequence?,
    private val lineMover: (Int) -> Boolean,
    private val mainExecutor: Executor,
    /**
     * The pointer moved to screen point (x, y), with lines of the given height. [detached] is
     * false while it sits on the caret, so the caret alone shows where it is.
     */
    private val onPointer: (x: Float, y: Float, lineHeight: Float, detached: Boolean) -> Unit = { _, _, _, _ -> },
) : TrackpadEditor {

    val cursor = TrackpadCursor(this)

    override val canHitTest: Boolean = Build.VERSION.SDK_INT >= 34 &&
        info?.supportedHandwritingGestures?.contains(InsertGesture::class.java) == true

    /** Starts the editor's reports. Returns false if the editor won't send them. */
    fun start(): Boolean {
        var mode = InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR
        if (Build.VERSION.SDK_INT >= 33) {
            mode = mode or InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER or
                InputConnection.CURSOR_UPDATE_FILTER_EDITOR_BOUNDS
        }
        if (Build.VERSION.SDK_INT >= 34) mode = mode or InputConnection.CURSOR_UPDATE_FILTER_VISIBLE_LINE_BOUNDS
        return ic.requestCursorUpdates(mode)
    }

    fun stop() {
        ic.requestCursorUpdates(0)
    }

    fun onCursorAnchorInfo(info: CursorAnchorInfo) {
        val g = toGeometry(info) ?: return
        if (debug) Log.d(TAG, "report sel=${g.selStart}..${g.selEnd} x=${g.x} y=${g.top}..${g.bottom} lines=${g.lines.size}")
        cursor.onGeometry(g, now())
        notifyPointer()
    }

    fun onSelection(start: Int, end: Int) {
        if (debug) Log.d(TAG, "selection $start..$end")
        cursor.onSelection(start, end, now())
    }

    fun onPointerDelta(dx: Float, dy: Float) {
        cursor.onPointerDelta(dx, dy, now())
        notifyPointer()
    }

    private fun notifyPointer() {
        if (cursor.ready) onPointer(cursor.pointerX, cursor.pointerY, cursor.caretHeight, cursor.pointerDetached)
    }

    override fun setCursor(offset: Int) {
        if (debug) Log.d(TAG, "setCursor $offset")
        ic.setSelection(offset, offset)
    }

    override fun step(from: Int, steps: Int): Int =
        text?.let { moveByGraphemes(it, from, steps) } ?: (from + steps).coerceAtLeast(0)

    override fun hitTest(x: Float, y: Float, halfWidth: Float) {
        if (Build.VERSION.SDK_INT >= 34) placeCaretAt(x, y) else cursor.onHitTestFailed(now())
    }

    /**
     * Inserts nothing at screen point ([x], [y]): the editor puts its caret there. Unlike a
     * select gesture, nothing is ever selected, so no selection highlight flashes.
     */
    @RequiresApi(34)
    private fun placeCaretAt(x: Float, y: Float) {
        if (debug) Log.d(TAG, "hitTest x=$x y=$y")
        val gesture = InsertGesture.Builder()
            .setInsertionPoint(android.graphics.PointF(x, y))
            .setTextToInsert("")
            .build()
        ic.performHandwritingGesture(gesture, mainExecutor) { result ->
            if (debug) Log.d(TAG, "hitTest result=$result")
            if (result != InputConnection.HANDWRITING_GESTURE_RESULT_SUCCESS) cursor.onHitTestFailed(now())
        }
    }

    override fun moveLines(lines: Int): Boolean {
        if (debug) Log.d(TAG, "moveLines $lines")
        return lineMover(lines)
    }

    private fun now() = SystemClock.uptimeMillis()

    private val debug = Log.isLoggable(TAG, Log.DEBUG)

    companion object {
        /** Enable logs with `adb shell setprop log.tag.Trackpad DEBUG`. */
        private const val TAG = "Trackpad"

        /**
         * The editor's report in screen pixels. Kept without an insertion marker (x = NaN), as
         * that is how a hit test's selection is reported, and it must be collapsed.
         */
        fun toGeometry(info: CursorAnchorInfo): CaretGeometry? {
            val x = info.insertionMarkerHorizontal
            if (info.selectionStart < 0) return null
            val m = info.matrix
            val pts = floatArrayOf(x, info.insertionMarkerTop, x, info.insertionMarkerBottom)
            m.mapPoints(pts)
            val lines = if (Build.VERSION.SDK_INT >= 34) {
                info.visibleLineBounds.map { r -> RectF(r).also { m.mapRect(it) }.toBand() }
            } else emptyList()
            val editor = if (Build.VERSION.SDK_INT >= 33) {
                info.editorBoundsInfo?.editorBounds?.let { RectF(it).also { r -> m.mapRect(r) } }
            } else null
            return CaretGeometry(
                selStart = info.selectionStart,
                selEnd = info.selectionEnd,
                x = pts[0],
                top = pts[1],
                bottom = pts[3],
                lines = lines,
                editorLeft = editor?.left ?: Float.NaN,
                editorRight = editor?.right ?: Float.NaN,
            )
        }

        private fun RectF.toBand() = LineBand(left, top, right, bottom)
    }
}
