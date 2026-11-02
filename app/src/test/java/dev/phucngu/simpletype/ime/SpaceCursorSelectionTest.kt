package dev.phucngu.simpletype.ime

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Horizontal space-drag moves the cursor with one setSelection per frame instead of one arrow key
 * per character: no key-event backlog for the app to chew through, so fast drags don't lag behind
 * the finger. The field's text is read once per gesture. Arrow keys stay for terminals and for
 * editors that can't report their text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpaceCursorSelectionTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class Editor(view: View, var text: String, var cursor: Int) : BaseInputConnection(view, false) {
        val keys = mutableListOf<Int>()
        var selections = 0
        var extractions = 0

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            extractions++
            return ExtractedText().apply {
                text = this@Editor.text
                startOffset = 0
                selectionStart = cursor
                selectionEnd = cursor
            }
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            selections++
            cursor = start
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = text.substring(0, cursor).takeLast(n)
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = text.substring(cursor).take(n)
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun finishComposingText(): Boolean = true

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            keys += event.keyCode
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> cursor = maxOf(0, cursor - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> cursor = minOf(text.length, cursor + 1)
                KeyEvent.KEYCODE_DPAD_UP -> cursor = maxOf(0, text.lastIndexOf('\n', cursor - 1))
            }
            return true
        }
    }

    private class TestIme(private val ic: InputConnection, private val type: Int) : SimpleTypeIME() {
        override fun getCurrentInputConnection(): InputConnection = ic
        override fun getCurrentInputEditorInfo(): EditorInfo = EditorInfo().apply { inputType = type }
    }

    private fun setup(
        text: String,
        cursor: Int,
        type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
    ): Pair<Editor, SimpleTypeIME> {
        val editor = Editor(View(ctx), text, cursor)
        return editor to TestIme(editor, type)
    }

    @Test
    fun horizontal_move_sets_the_selection_once_without_arrow_keys() {
        val (editor, ime) = setup("hello world", cursor = 0)
        ime.onSpaceCursorMove(6, 0)
        assertEquals(6, editor.cursor)
        assertEquals(1, editor.selections)
        assertEquals(emptyList<Int>(), editor.keys)
    }

    @Test
    fun text_is_read_once_per_gesture() {
        val (editor, ime) = setup("hello world", cursor = 0)
        ime.onSpaceCursorMove(2, 0)
        ime.onSpaceCursorMove(3, 0)
        ime.onSpaceCursorMove(-1, 0)
        assertEquals(4, editor.cursor)
        assertEquals(1, editor.extractions)
    }

    @Test
    fun clamps_at_the_ends_of_the_text() {
        val (editor, ime) = setup("hello", cursor = 2)
        ime.onSpaceCursorMove(-10, 0)
        assertEquals(0, editor.cursor)
        ime.onSpaceCursorMove(50, 0)
        assertEquals(5, editor.cursor)
        assertEquals(emptyList<Int>(), editor.keys)
    }

    @Test
    fun steps_over_emoji_as_one_character() {
        val (editor, ime) = setup("a😀b", cursor = 1)
        ime.onSpaceCursorMove(1, 0)
        assertEquals(3, editor.cursor)
    }

    @Test
    fun a_new_gesture_reads_the_text_again() {
        val (editor, ime) = setup("hello", cursor = 0)
        ime.onSpaceCursorMove(2, 0)
        ime.onSpaceCursorEnd()
        editor.text = "hi there, hello"
        editor.cursor = 9
        ime.onSpaceCursorMove(1, 0)
        assertEquals(10, editor.cursor)
        assertEquals(2, editor.extractions)
    }

    @Test
    fun line_move_then_sideways_move_starts_from_the_new_line() {
        val text = "first line\nsecond"
        val (editor, ime) = setup(text, cursor = text.length)
        ime.onSpaceCursorMove(-1, 0)
        ime.onSpaceCursorMove(0, -1) // DPAD up: cursor goes to the newline at 10
        ime.onSpaceCursorMove(-2, 0)
        assertEquals(8, editor.cursor)
    }

    @Test
    fun terminals_still_get_arrow_keys() {
        val (editor, ime) = setup("ls -la", cursor = 6, type = InputType.TYPE_NULL)
        ime.onSpaceCursorMove(-2, 0)
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT), editor.keys)
        assertEquals(0, editor.selections)
    }
}
