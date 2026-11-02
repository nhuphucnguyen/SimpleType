package dev.phucngu.simpletype.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Trackpad-style space-drag: the finger moves a pointer in screen pixels and the caret follows to
 * the character under it, using the caret geometry the editor reports back.
 *
 * [FakeEditor] lays text out in soft-wrapped lines with proportional widths (W=40px, i=10px,
 * others 20px), lines 60px tall starting at (100, 200). Like real editors it reports geometry
 * asynchronously: nothing arrives until [FakeEditor.deliver] is called (or autoDeliver is on).
 */
class TrackpadCursorTest {

    @Test
    fun `caret follows on-screen distance, not character count`() {
        val ed = FakeEditor(listOf("WWWWiiiiWWWW"))
        val tc = ed.start(caret = 0)
        // Across the four 40px W's: 160px lands after them, at offset 4.
        tc.drag(160f, 0f)
        assertEquals(4, ed.caret)
        // The same 40px that crossed one W now crosses four i's.
        tc.drag(40f, 0f)
        assertEquals(8, ed.caret)
    }

    @Test
    fun `small wobble around a boundary does not flicker the caret`() {
        val ed = FakeEditor(listOf("aaaaaaaaaa"))
        val tc = ed.start(caret = 3)
        val moves = ed.setCursorCalls
        repeat(10) { tc.drag(if (it % 2 == 0) 9f else -9f, 0f) }
        assertEquals(moves, ed.setCursorCalls)
        assertEquals(3, ed.caret)
    }

    @Test
    fun `settles on a wide character without oscillating`() {
        val ed = FakeEditor(listOf("iiiiWWWWiiii"))
        val tc = ed.start(caret = 0)
        // Learn the narrow width first, then jump onto the wide letters in one frame.
        tc.drag(40f, 0f)
        tc.onPointerDelta(100f, 0f, 3000) // one fast frame: pointer at x = 240, mid third W
        repeat(10) { tc.drag(0f, 0f) }
        val settled = ed.caret
        val calls = ed.setCursorCalls
        repeat(10) { tc.drag(0f, 0f) }
        assertEquals(calls, ed.setCursorCalls)
        assertTrue("caret next to pointer, was $settled", settled == 6 || settled == 7)
    }

    @Test
    fun `stale geometry while a move is in flight does not trigger a second move`() {
        val ed = FakeEditor(listOf("aaaaaaaaaaaaaaaa"), autoDeliver = false)
        val tc = ed.start(caret = 0)
        tc.onPointerDelta(60f, 0f, 16)
        assertTrue(ed.caret in 1..3)
        // The editor re-reports the old caret (e.g. a scroll) before acknowledging the move.
        tc.onGeometry(ed.geometryFor(0), 20)
        tc.onPointerDelta(0f, 0f, 32)
        assertEquals(1, ed.setCursorCalls - ed.initialCalls)
        ed.deliver(tc)
        assertEquals(3, ed.caret)
    }

    @Test
    fun `keeps moving between reports by predicting the caret`() {
        val ed = FakeEditor(listOf("aaaaaaaaaaaaaaaaaaaa"), autoDeliver = false)
        val tc = ed.start(caret = 0)
        tc.onPointerDelta(40f, 0f, 16)
        val first = ed.caret
        tc.onPointerDelta(40f, 0f, 32) // no report yet
        assertTrue("moved again before the report, ${ed.caret} vs $first", ed.caret > first)
    }

    @Test
    fun `moving down a line asks the editor for the character under the pointer`() {
        val ed = FakeEditor(listOf("aaaaaaaaaa", "WWWWWWWWWW"), canHitTest = true)
        val tc = ed.start(caret = 4) // x = 180 on line 0
        tc.drag(0f, 60f)
        // Pointer at x = 180 on line 1: inside the third W (160..200) -> boundary 12 or 13.
        assertTrue("was ${ed.caret}", ed.caret == 12 || ed.caret == 13)
        assertEquals(1, ed.hitTests)
        assertEquals(ed.caret, ed.selEnd) // the hit-test selection was collapsed
    }

    @Test
    fun `vertical wobble smaller than a line does not change line`() {
        val ed = FakeEditor(listOf("aaaaaaaaaa", "aaaaaaaaaa"), canHitTest = true)
        val tc = ed.start(caret = 4)
        tc.drag(0f, 20f)
        tc.drag(0f, -35f)
        tc.drag(0f, 30f)
        assertEquals(4, ed.caret)
        assertEquals(0, ed.hitTests + ed.lineMoves)
    }

    @Test
    fun `without hit testing a line change uses line moves then lines up horizontally`() {
        val ed = FakeEditor(listOf("aaaaaaaaaa", "aaaaaaaaaa"), canHitTest = false)
        val tc = ed.start(caret = 2)
        tc.drag(60f, 60f) // pointer x = 100 + 40 + 60 = 200 -> offset 5 on line 1 = 15
        assertEquals(1, ed.lineMoves)
        assertEquals(15, ed.caret)
    }

    @Test
    fun `a failed hit test falls back to a line move`() {
        val ed = FakeEditor(listOf("aaaaaaaaaa", "aaaaaaaaaa"), canHitTest = true, hitTestWorks = false)
        val tc = ed.start(caret = 4)
        tc.drag(0f, 60f)
        assertEquals(1, ed.lineMoves)
        assertEquals(14, ed.caret)
    }

    @Test
    fun `after a failed hit test, later line changes go straight to line moves`() {
        val ed = FakeEditor(listOf("aaaaaaaaaa", "aaaaaaaaaa", "aaaaaaaaaa"), canHitTest = true, hitTestWorks = false)
        val tc = ed.start(caret = 4)
        tc.drag(0f, 60f)
        tc.drag(0f, 60f)
        assertEquals(1, ed.hitTests)
        assertEquals(2, ed.lineMoves)
        assertEquals(24, ed.caret)
    }

    @Test
    fun `a hit test that never reports a selection times out to a line move`() {
        val ed = FakeEditor(listOf("aaaaaaaaaa", "aaaaaaaaaa"), canHitTest = true, hitTestSilent = true)
        val tc = ed.start(caret = 4)
        tc.drag(0f, 60f)
        assertEquals(4, ed.caret)
        // Later frames, after the hit test should have answered.
        tc.onPointerDelta(0f, 0f, clock + 200)
        assertEquals(1, ed.lineMoves)
        assertEquals(14, ed.caret)
        tc.drag(-40f, 0f)
        assertEquals(12, ed.caret)
    }

    @Test
    fun `a hit test answered with a plain caret is accepted`() {
        val ed = FakeEditor(listOf("aaaaaaaaaa", "aaaaaaaaaa"), canHitTest = true, hitTestCollapses = true)
        val tc = ed.start(caret = 4)
        tc.drag(0f, 60f)
        assertEquals(14, ed.caret)
        tc.drag(-40f, 0f)
        assertEquals(12, ed.caret)
        assertEquals(0, ed.lineMoves)
    }

    @Test
    fun `reports arriving one move late (EditText, Compose) still track`() {
        val ed = FakeEditor(listOf("WWWWiiiiWWWWiiii"), lagReports = true)
        val tc = ed.start(caret = 0)
        val seen = mutableListOf<Int>()
        repeat(16) { tc.drag(10f, 0f); seen += ed.caret }
        repeat(5) { tc.drag(0f, 0f) }
        // 160px: the four W's.
        assertEquals(4, ed.caret)
        assertEquals("never moves backward on a rightward drag: $seen", seen, seen.sorted())
        repeat(4) { tc.drag(10f, 0f) }
        repeat(5) { tc.drag(0f, 0f) }
        assertEquals(8, ed.caret)
    }

    @Test
    fun `hit test in an editor reporting position before selection (Chrome)`() {
        val ed = FakeEditor(listOf("aaaaaaaaaa", "aaaaaaaaaa", "aaaaaaaaaa"), canHitTest = true, staleSelection = true)
        val tc = ed.start(caret = 4)
        tc.drag(0f, 60f)
        assertEquals(14, ed.caret)
        tc.drag(0f, 60f)
        assertEquals(24, ed.caret)
        assertEquals(0, ed.lineMoves)
        assertEquals(ed.caret, ed.selEnd)
    }

    @Test
    fun `dragging past a wrapped line end stops at the end instead of bouncing`() {
        val ed = FakeEditor(listOf("aaaaa", "bbbbbbbbbb"), canHitTest = true)
        val tc = ed.start(caret = 3)
        // Far past the 100px-wide first line; offset 5 is shown at the start of line 1.
        repeat(5) { tc.drag(80f, 0f) }
        repeat(10) { tc.drag(0f, 0f) }
        assertEquals(5, ed.caret)
        assertEquals(0, ed.hitTests + ed.lineMoves)
        val calls = ed.setCursorCalls
        repeat(10) { tc.drag(0f, 0f) }
        assertEquals(calls, ed.setCursorCalls)
    }

    @Test
    fun `pointer stays free past a line end, like a trackpad`() {
        val ed = FakeEditor(listOf("aaaaa", "bbbbbbbbbb"), canHitTest = true)
        val tc = ed.start(caret = 3)
        repeat(3) { tc.drag(80f, 0f) } // pointer at x = 400, the line ends at 200
        assertEquals(5, ed.caret)
        tc.drag(-40f, 0f) // still past the end
        assertEquals(5, ed.caret)
        tc.drag(-180f, 0f) // x = 180
        assertEquals(4, ed.caret)
    }

    @Test
    fun `floating cursor only shows when the pointer leaves the caret`() {
        val ed = FakeEditor(listOf("aaaaa", "bbbbbbbbbb"), canHitTest = true)
        val tc = ed.start(caret = 1)
        tc.drag(40f, 0f) // caret follows: one cursor is enough
        assertFalse(tc.pointerDetached)
        repeat(4) { tc.drag(5f, 0f) } // slow drag along the line, to x = 180
        assertFalse(tc.pointerDetached)
        tc.drag(120f, 0f) // past the line's end (x = 200): the caret stays, the pointer goes on
        assertEquals(5, ed.caret)
        assertTrue(tc.pointerDetached)
        tc.drag(-150f, 0f) // back over the text
        assertFalse(tc.pointerDetached)
    }

    @Test
    fun `a short line in between does not lose the column`() {
        val ed = FakeEditor(listOf("aaaaaaaaaa", "aaa", "aaaaaaaaaa"), canHitTest = true)
        val tc = ed.start(caret = 8) // x = 260
        tc.drag(0f, 60f)
        assertTrue("on the short line's end, was ${ed.caret}", ed.caret in 12..13)
        tc.drag(0f, 60f)
        assertEquals(21, ed.caret)
    }

    @Test
    fun `a short line in between does not lose the column, with line moves`() {
        val ed = FakeEditor(listOf("aaaaaaaaaa", "aaa", "aaaaaaaaaa"), canHitTest = false)
        val tc = ed.start(caret = 8)
        tc.drag(0f, 60f)
        tc.drag(0f, 60f)
        assertEquals(21, ed.caret)
    }

    @Test
    fun `a report without caret position does not break tracking`() {
        val ed = FakeEditor(listOf("aaaaaaaaaaaaaaaa"))
        val tc = ed.start(caret = 2)
        // E.g. the app selected text itself: no insertion marker in the report.
        tc.onGeometry(ed.geometryFor(2, 5), 3000)
        tc.onGeometry(ed.geometryFor(2), 3001)
        tc.drag(40f, 0f)
        assertEquals(4, ed.caret)
    }

    @Test
    fun `merges fragmented line rects into one band per line`() {
        val bands = TrackpadCursor.mergeLines(
            listOf(
                LineBand(0f, 0f, 50f, 23f), LineBand(50f, 1f, 120f, 23f), // one line, two runs
                LineBand(0f, 24f, 80f, 47f),
            )
        )
        assertEquals(2, bands.size)
        assertEquals(LineBand(0f, 0f, 120f, 23f), bands[0])
    }

    // ---- Fake editor ----

    private inner class FakeEditor(
        val lines: List<String>,
        override val canHitTest: Boolean = false,
        val hitTestWorks: Boolean = true,
        /** Reports success but never sends the selection (seen in Chrome). */
        val hitTestSilent: Boolean = false,
        /** Places a caret at the hit character instead of selecting it. */
        val hitTestCollapses: Boolean = false,
        /**
         * Like Chrome around hit tests: the report after a hit test, and the one after collapsing
         * its selection, carry the previous selection numbers with the new caret position; the
         * real selection follows through onSelection.
         */
        val staleSelection: Boolean = false,
        /**
         * Like EditText and Compose: the selection is announced at once, but each geometry
         * report arrives only after the next change (one behind).
         */
        val lagReports: Boolean = false,
        var autoDeliver: Boolean = true,
    ) : TrackpadEditor {
        val text = lines.joinToString("")
        var caret = 0
        var selEnd = 0
        var setCursorCalls = 0
        var initialCalls = 0
        var hitTests = 0
        var lineMoves = 0
        private var cursorTracker: TrackpadCursor? = null
        private var pending = false
        private var time = 1000L

        fun width(c: Char) = when (c) { 'W' -> 40f; 'i' -> 10f; else -> 20f }
        fun lineStart(k: Int) = lines.take(k).sumOf { it.length }
        /** Like real editors, an offset at a soft wrap is shown at the start of the next line. */
        fun lineOf(offset: Int): Int {
            var k = 0
            while (k + 1 < lines.size && offset >= lineStart(k + 1)) k++
            return k
        }
        fun xOf(offset: Int): Float {
            val k = lineOf(offset)
            return 100f + text.substring(lineStart(k), offset).sumOf { width(it).toDouble() }.toFloat()
        }
        fun band(k: Int) = LineBand(100f, 200f + 60f * k, 100f + lines[k].sumOf { width(it).toDouble() }.toFloat(), 260f + 60f * k)

        fun geometryFor(start: Int, end: Int = start): CaretGeometry {
            val k = lineOf(end)
            return CaretGeometry(
                selStart = start, selEnd = end,
                // Editors report no insertion marker while text is selected.
                x = if (start == end) xOf(end) else Float.NaN, top = 200f + 60f * k, bottom = 260f + 60f * k,
                lines = lines.indices.map { band(it) },
                editorLeft = 80f, editorRight = 700f,
            )
        }

        fun start(caret: Int): TrackpadCursor {
            this@TrackpadCursorTest.currentEditor = this
            this.caret = caret
            selEnd = caret
            val tc = TrackpadCursor(this)
            cursorTracker = tc
            tc.onGeometry(geometryFor(caret), time)
            reportedStart = caret
            reportedEnd = caret
            initialCalls = setCursorCalls
            return tc
        }

        private var reportedStart = 0
        private var reportedEnd = 0
        private var staleNext = false
        private val lagged = ArrayDeque<CaretGeometry>()

        fun deliver(tc: TrackpadCursor) {
            if (!pending) return
            pending = false
            val g = geometryFor(caret, selEnd)
            // Bookkeeping first: the callbacks below may trigger nested deliveries.
            val previousStart = reportedStart
            val previousEnd = reportedEnd
            val c = caret
            val e = selEnd
            reportedStart = caret
            reportedEnd = selEnd
            when {
                staleSelection && staleNext -> {
                    staleNext = false
                    tc.onGeometry(g.copy(selStart = previousStart, selEnd = previousEnd, x = xOf(e)), ++time)
                    tc.onSelection(c, e, ++time)
                }
                lagReports -> {
                    tc.onSelection(c, e, ++time)
                    lagged.addLast(g)
                    if (lagged.size > 1) tc.onGeometry(lagged.removeFirst(), ++time)
                }
                else -> {
                    tc.onGeometry(g, ++time)
                    tc.onSelection(c, e, ++time)
                }
            }
        }

        /** Delivers a lagging report once the editor goes quiet, as real editors eventually do. */
        fun flush(tc: TrackpadCursor) {
            while (lagged.isNotEmpty()) tc.onGeometry(lagged.removeFirst(), ++time)
        }

        private fun changed() {
            pending = true
            if (autoDeliver) deliver(cursorTracker!!)
        }

        override fun setCursor(offset: Int) {
            if (selEnd != caret) staleNext = true
            setCursorCalls++
            caret = offset.coerceIn(0, text.length)
            selEnd = caret
            changed()
        }

        override fun step(from: Int, steps: Int): Int = (from + steps).coerceIn(0, text.length)

        override fun hitTest(x: Float, y: Float, halfWidth: Float) {
            hitTests++
            val k = ((y - 200f) / 60f).toInt().coerceIn(0, lines.size - 1)
            val start = lineStart(k)
            val hits = lines[k].indices.filter {
                val center = xOf(start + it) + width(lines[k][it]) / 2f
                center in (x - halfWidth)..(x + halfWidth)
            }
            if (!hitTestWorks || hits.isEmpty()) {
                cursorTracker!!.onHitTestFailed(++time)
                return
            }
            if (hitTestSilent) return
            staleNext = true
            caret = start + hits.first()
            selEnd = if (hitTestCollapses) caret else start + hits.last() + 1
            changed()
        }

        override fun moveLines(lines: Int): Boolean {
            lineMoves++
            val x = xOf(caret)
            val k = (lineOf(caret) + lines).coerceIn(0, this.lines.size - 1)
            val start = lineStart(k)
            val line = this.lines[k]
            val i = (0..line.length).minByOrNull { abs(100f + line.take(it).sumOf { c -> width(c).toDouble() }.toFloat() - x) }!!
            caret = start + i
            selEnd = caret
            changed()
            return true
        }

    }

    private var clock = 5000L
    private var currentEditor: FakeEditor? = null

    /** Finger movement delivered in frames of at most 20px, as a real drag arrives. */
    private fun TrackpadCursor.drag(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) currentEditor?.flush(this)
        val frames = maxOf(1, (maxOf(abs(dx), abs(dy)) / 20f).toInt())
        repeat(frames) {
            clock += 16
            onPointerDelta(dx / frames, dy / frames, clock)
        }
    }
}
