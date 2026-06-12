package dev.phucngu.simpletype.ime

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

    /** Minimal InputConnection backed by a buffer, cursor assumed at the end (no selection). */
    private class FakeIc(view: View, initial: String) : BaseInputConnection(view, false) {
        val text = StringBuilder(initial)
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
            text.substring(maxOf(0, text.length - n))
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val d = minOf(beforeLength, text.length)
            text.delete(text.length - d, text.length)
            return true
        }
    }

    /** Test seam: feed our fake connection/editor-info and a real (but headless) keyboard view. */
    private class TestIme(
        private val kv: LatinKeyboardView,
        private val ic: InputConnection,
        private val info: EditorInfo?,
    ) : SimpleTypeIME() {
        init {
            SimpleTypeIME::class.java.getDeclaredField("keyboardView").apply {
                isAccessible = true
                set(this@TestIme, kv)
            }
        }
        override fun getCurrentInputConnection(): InputConnection = ic
        override fun getCurrentInputEditorInfo(): EditorInfo? = info
    }

    private fun shift() = Key(KeyCode.SHIFT, "Shift")
    private fun del() = Key(KeyCode.DELETE, "Delete")

    @Test
    fun shift_then_delete_thrice_removes_three_words() {
        val ic = FakeIc(View(ctx), "the quick brown fox")
        val ime = TestIme(LatinKeyboardView(ctx), ic, null)

        ime.onKey(shift())   // arm shift once
        ime.onKey(del())     // delete "fox"
        ime.onKey(del())     // delete "brown"
        ime.onKey(del())     // delete "quick"

        assertEquals("the ", ic.text.toString())
    }
}
