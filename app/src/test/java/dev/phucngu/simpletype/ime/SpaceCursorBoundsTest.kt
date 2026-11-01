package dev.phucngu.simpletype.ime

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Space-drag past the first/last line (or past either end of the text) must not send arrow keys
 * the editor can't handle: Android's EditText then moves focus to a neighbouring view, and the
 * cursor disappears. An up on the first line still moves to the start of the text, like the editor's
 * own behaviour; only the keys after that are held back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpaceCursorBoundsTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Mimics EditText's ArrowKeyMovementMethod over newline-separated lines. */
    private class Editor(view: View, val text: String, var cursor: Int) : BaseInputConnection(view, false) {
        var focusLost = false

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
            if (focusLost) "" else text.substring(0, cursor).takeLast(n)

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence =
            if (focusLost) "" else text.substring(cursor).take(n)

        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun finishComposingText(): Boolean = true

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN || focusLost) return true
            val handled = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> move(cursor - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> move(cursor + 1)
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
                    if (lineStart > 0) {
                        val prevStart = text.lastIndexOf('\n', lineStart - 2) + 1
                        move(minOf(prevStart + (cursor - lineStart), lineStart - 1))
                    } else move(0)
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
                    val lineEnd = text.indexOf('\n', cursor)
                    if (lineEnd >= 0) {
                        val nextEnd = text.indexOf('\n', lineEnd + 1).let { if (it < 0) text.length else it }
                        move(minOf(lineEnd + 1 + (cursor - lineStart), nextEnd))
                    } else move(text.length)
                }
                else -> true
            }
            if (!handled) focusLost = true // unhandled arrow: focus search moves to another view
            return true
        }

        private fun move(to: Int): Boolean {
            if (to < 0 || to > text.length || to == cursor) return false
            cursor = to
            return true
        }
    }

    private class TestIme(private val ic: InputConnection) : SimpleTypeIME() {
        override fun getCurrentInputConnection(): InputConnection = ic
        override fun getCurrentInputEditorInfo(): EditorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
    }

    private val text = "first line\nsecond line\nthird"

    private fun setup(cursor: Int): Pair<Editor, SimpleTypeIME> {
        val editor = Editor(View(ctx), text, cursor)
        return editor to TestIme(editor)
    }

    @Test
    fun dragging_up_past_first_line_stops_at_start() {
        val (editor, ime) = setup(cursor = text.indexOf("line\nthird"))
        ime.onSpaceCursorMove(0, -5)
        assertFalse(editor.focusLost)
        assertEquals(0, editor.cursor)
    }

    @Test
    fun dragging_up_one_line_at_a_time_past_top_keeps_focus() {
        val (editor, ime) = setup(cursor = text.indexOf("line\nthird"))
        repeat(6) { ime.onSpaceCursorMove(0, -1) }
        assertFalse(editor.focusLost)
        assertEquals(0, editor.cursor)
    }

    @Test
    fun dragging_down_past_last_line_stops_at_end() {
        val (editor, ime) = setup(cursor = 2)
        ime.onSpaceCursorMove(0, 5)
        assertFalse(editor.focusLost)
        assertEquals(text.length, editor.cursor)
    }

    @Test
    fun dragging_left_past_start_keeps_focus() {
        val (editor, ime) = setup(cursor = 3)
        ime.onSpaceCursorMove(-10, 0)
        assertFalse(editor.focusLost)
        assertEquals(0, editor.cursor)
    }

    @Test
    fun dragging_right_past_end_keeps_focus() {
        val (editor, ime) = setup(cursor = text.length - 2)
        ime.onSpaceCursorMove(10, 0)
        assertFalse(editor.focusLost)
        assertEquals(text.length, editor.cursor)
    }

    @Test
    fun moving_within_text_still_works() {
        val (editor, ime) = setup(cursor = text.length)
        ime.onSpaceCursorMove(0, -1)
        assertEquals(text.indexOf("second") + 5, editor.cursor)
        ime.onSpaceCursorMove(-2, 0)
        assertEquals(text.indexOf("second") + 3, editor.cursor)
    }
}
