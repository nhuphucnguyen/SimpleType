package dev.phucngu.simpletype.ime

import android.os.Looper
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import dev.phucngu.simpletype.ime.keyboard.model.Key
import dev.phucngu.simpletype.voice.VoiceLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * A key typed while the debounced selection re-sync is still waiting must not let that re-sync
 * run later with the cursor it was scheduled for: it used to pick up the word at a stale
 * position, so the next letter replaced the space ("giải th" → "giảith|t").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectionSyncRaceTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** An editor that tracks text, cursor and composing region like a real one, and reports updates. */
    private class Editor(view: View) : BaseInputConnection(view, false) {
        var text = ""
        var cursor = 0
        var compStart = -1
        var compEnd = -1
        var ime: SimpleTypeIME? = null

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = text.substring(0, cursor).takeLast(n)

        override fun getSelectedText(flags: Int): CharSequence? = null

        private fun replace(start: Int, end: Int, s: String) {
            val old = cursor
            text = text.substring(0, start) + s + text.substring(end)
            cursor = start + s.length
            report(old)
        }

        private fun report(old: Int) {
            ime?.onUpdateSelection(old, old, cursor, cursor, compStart, compEnd)
        }

        override fun setComposingText(t: CharSequence?, newCursorPosition: Int): Boolean {
            val s = t?.toString().orEmpty()
            val (start, end) = if (compStart >= 0) compStart to compEnd else cursor to cursor
            compStart = start
            compEnd = start + s.length
            replace(start, end, s)
            return true
        }

        override fun commitText(t: CharSequence?, newCursorPosition: Int): Boolean {
            val s = t?.toString().orEmpty()
            val (start, end) = if (compStart >= 0) compStart to compEnd else cursor to cursor
            compStart = -1
            compEnd = -1
            replace(start, end, s)
            return true
        }

        override fun finishComposingText(): Boolean {
            compStart = -1
            compEnd = -1
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            compStart = start
            compEnd = end
            return true
        }

        override fun deleteSurroundingText(before: Int, after: Int): Boolean {
            replace(cursor - before, cursor, "")
            return true
        }
    }

    private class TestIme(private val ic: InputConnection) : SimpleTypeIME() {
        init {
            SimpleTypeIME::class.java.getDeclaredField("language").apply { isAccessible = true }
                .set(this, VoiceLanguage.VIETNAMESE)
        }
        override fun getCurrentInputConnection(): InputConnection = ic
        override fun getCurrentInputEditorInfo(): EditorInfo? = null
    }

    private fun type(ime: SimpleTypeIME, keys: String) = keys.forEach {
        ime.onKey(Key(it.code, it.toString()))
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    @Test
    fun fast_typing_after_space_keeps_words_apart() {
        val editor = Editor(View(ctx))
        val ime = TestIme(editor)
        editor.ime = ime

        type(ime, "giair")
        type(ime, " ") // schedules a re-sync for the cursor after the space
        type(ime, "t") // before the re-sync runs
        idle(200)
        type(ime, "hichs")

        assertEquals("giải thích", editor.text)
        assertEquals(editor.text.length, editor.cursor)
    }

    @Test
    fun cursor_move_then_fast_key_still_picks_up_word() {
        val editor = Editor(View(ctx))
        val ime = TestIme(editor)
        editor.ime = ime
        editor.text = "cong hoa"
        editor.cursor = 8

        // The user taps right after "cong" and types at once.
        editor.cursor = 4
        ime.onUpdateSelection(8, 8, 4, 4, -1, -1)
        type(ime, "o")
        idle(200)

        assertEquals("công hoa", editor.text)
        assertEquals(4, editor.cursor)
    }
}
