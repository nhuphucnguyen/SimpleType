package dev.phucngu.simpletype.ime

import dev.phucngu.simpletype.ime.keyboard.model.Key
import dev.phucngu.simpletype.ime.keyboard.model.KeyCode

import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives [SimpleTypeIME.onKey] through Robolectric to cover the Shift+Delete word-delete path,
 * which lives in the [android.inputmethodservice.InputMethodService] (not reachable from the
 * plain-JVM [dev.phucngu.simpletype.text.TelexEngine] tests).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShiftDeleteTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    /**
     * Minimal InputConnection backed by a buffer, cursor assumed at the end (no selection).
     * Models a composing region so Telex (Vietnamese) word-in-progress paths can be exercised:
     * [text] holds committed text, [composing] the active composing span, and [visible] their
     * concatenation as the editor would render and report it.
     */
    private class FakeIc(view: View, initial: String) : BaseInputConnection(view, false) {
        val text = StringBuilder(initial)
        private var composing = ""
        fun visible(): String = text.toString() + composing
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
            val full = visible()
            return full.substring(maxOf(0, full.length - n))
        }
        override fun setComposingText(t: CharSequence, newCursorPosition: Int): Boolean {
            composing = t.toString()
            return true
        }
        override fun commitText(t: CharSequence, newCursorPosition: Int): Boolean {
            composing = ""
            text.append(t)
            return true
        }
        override fun finishComposingText(): Boolean {
            text.append(composing)
            composing = ""
            return true
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val d = minOf(beforeLength, text.length)
            text.delete(text.length - d, text.length)
            return true
        }
    }

    /** Test seam: feed our fake connection/editor-info and a real (but headless) keyboard view. */
    private class TestIme(
        private val ic: InputConnection,
        private val info: EditorInfo?,
    ) : SimpleTypeIME() {
        override fun getCurrentInputConnection(): InputConnection = ic
        override fun getCurrentInputEditorInfo(): EditorInfo? = info
    }

    private fun shift() = Key(KeyCode.SHIFT, "Shift")
    private fun del() = Key(KeyCode.DELETE, "Delete")
    private fun typeLetter(c: Char) = Key(c.code, c.toString())

    /** Flip the IME into Vietnamese so letters route through the Telex composing region. */
    private fun TestIme.setVietnamese() {
        SimpleTypeIME::class.java.getDeclaredField("language").apply {
            isAccessible = true
            set(this@setVietnamese, dev.phucngu.simpletype.voice.VoiceLanguage.VIETNAMESE)
        }
    }

    @Test
    fun held_shift_then_delete_thrice_removes_three_words() {
        val ic = FakeIc(View(ctx), "the quick brown fox")
        val ime = TestIme(ic, null)

        ime.onShiftHold(true) // hold Shift as a modifier
        ime.onKey(del())      // delete "fox"
        ime.onKey(del())      // delete "brown"
        ime.onKey(del())      // delete "quick"

        assertEquals("the ", ic.text.toString())
    }

    /**
     * A one-shot Shift (tapped because the user was about to type a capital, then changed their
     * mind) must NOT word-delete: Delete removes a single character. Only a held Shift word-deletes.
     */
    @Test
    fun one_shot_shift_then_delete_removes_single_char() {
        val ic = FakeIc(View(ctx), "the quick")
        val ime = TestIme(ic, null)

        ime.onKey(shift()) // one-shot caps arm, not a held modifier
        ime.onKey(del())

        assertEquals("the quic", ic.text.toString())
    }

    /**
     * Regression: in Vietnamese mode the word at the cursor sits in the Telex composing region
     * (no trailing space committed yet). Shift+Delete must drop the whole word, not backspace a
     * single character. Previously the composing branch ran first and only deleted one char, so
     * word-delete "only worked after a space".
     */
    @Test
    fun held_shift_then_delete_removes_word_still_being_composed() {
        val ic = FakeIc(View(ctx), "the ")
        val ime = TestIme(ic, null)
        ime.setVietnamese()

        "hello".forEach { ime.onKey(typeLetter(it)) } // composing region = "hello"
        ime.onShiftHold(true)
        ime.onKey(del())

        assertEquals("the ", ic.visible())
    }
}
