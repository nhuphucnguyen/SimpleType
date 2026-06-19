package dev.phucngu.simpletype.ime

import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import dev.phucngu.simpletype.voice.VoiceLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Vietnamese Telex is disabled in editors that handle composing text inconsistently — terminal
 * emulators like Termux (they report `inputType == TYPE_NULL`). There the underlined composing
 * buffer is unreliable, so Telex composition is turned off and letters commit raw, exactly like
 * English (which works fine in those editors). These tests drive the IME in Vietnamese mode with
 * `directCommit = true` and assert that no tone composition happens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalTelexTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    /**
     * A terminal-like editor: commits and deletions are honoured, composing text renders nothing,
     * and it reports no composing region (candidatesEnd = -1) on selection updates.
     */
    private class TerminalIc(view: View) : BaseInputConnection(view, false) {
        val sb = StringBuilder()
        var cursor = 0
        val visible: String get() = sb.toString()

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
            sb.substring(0, cursor).takeLast(n)

        override fun getSelectedText(flags: Int): CharSequence? = null

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true
        override fun finishComposingText(): Boolean = true
        override fun setComposingRegion(start: Int, end: Int): Boolean = true

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            sb.insert(cursor, text?.toString() ?: "")
            cursor += text?.length ?: 0
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val start = (cursor - beforeLength).coerceAtLeast(0)
            sb.delete(start, cursor)
            cursor = start
            return true
        }
    }

    private class TestIme(
        private val ic: InputConnection,
    ) : SimpleTypeIME() {
        init {
            set("language", VoiceLanguage.VIETNAMESE)
            set("directCommit", true)
        }

        private fun set(field: String, value: Any) {
            SimpleTypeIME::class.java.getDeclaredField(field)
                .apply { isAccessible = true }.set(this, value)
        }

        override fun getCurrentInputConnection(): InputConnection = ic
        override fun getCurrentInputEditorInfo(): EditorInfo? = null
    }

    /** Press one key and fire onUpdateSelection (no composing region) like the framework would. */
    private fun TestIme.press(ic: TerminalIc, key: Key, old: Int) {
        onKey(key)
        onUpdateSelection(old, old, ic.cursor, ic.cursor, -1, -1)
    }

    /** Type a word letter-by-letter; returns what is visible (no trailing space committed). */
    private fun typeWord(word: String): String {
        val ic = TerminalIc(View(ctx))
        val ime = TestIme(ic)
        var old = 0
        for (c in word) {
            ime.press(ic, Key(c.code, c.toString()), old)
            old = ic.cursor
        }
        return ic.visible
    }

    // Telex sequences come out as the raw letters typed — no tones, no vowel folding.
    @Test fun tieengs_stays_raw() = assertEquals("tieengs", typeWord("tieengs"))

    @Test fun as_stays_raw() = assertEquals("as", typeWord("as"))

    @Test fun ddaa_stays_raw() = assertEquals("ddaa", typeWord("ddaa"))

    @Test fun backspace_deletes_one_char() {
        val ic = TerminalIc(View(ctx))
        val ime = TestIme(ic)
        var old = 0
        for (c in "abc") {
            ime.press(ic, Key(c.code, c.toString()), old)
            old = ic.cursor
        }
        assertEquals("abc", ic.visible)
        ime.press(ic, Key(KeyCode.DELETE, ""), old)
        assertEquals("ab", ic.visible)
    }
}
