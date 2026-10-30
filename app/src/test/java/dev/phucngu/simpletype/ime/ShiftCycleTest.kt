package dev.phucngu.simpletype.ime

import dev.phucngu.simpletype.ime.keyboard.model.Key
import dev.phucngu.simpletype.ime.keyboard.model.KeyCode

import android.text.InputType
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
 * Shift tap cycle. A shift armed by auto-capitalisation is a suggestion, so tapping Shift turns
 * it off (lowercase) rather than escalating to caps lock. A shift the user armed themselves
 * still escalates to caps lock on the next tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShiftCycleTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class FakeIc(view: View, initial: String) : BaseInputConnection(view, false) {
        val text = StringBuilder(initial)
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
            val full = text.toString()
            return full.substring(maxOf(0, full.length - n))
        }
        override fun commitText(t: CharSequence, newCursorPosition: Int): Boolean {
            text.append(t)
            return true
        }
    }

    private class TestIme(
        private val ic: InputConnection,
        private val info: EditorInfo?,
    ) : SimpleTypeIME() {
        override fun getCurrentInputConnection(): InputConnection = ic
        override fun getCurrentInputEditorInfo(): EditorInfo? = info
    }

    private fun capSentencesInfo() = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }

    private fun shift() = Key(KeyCode.SHIFT, "Shift")
    private fun letter(c: Char) = Key(c.code, c.toString())

    private fun TestIme.autoCapitalize() {
        SimpleTypeIME::class.java.getDeclaredMethod("updateAutoCapitalize", EditorInfo::class.java)
            .apply { isAccessible = true }
            .invoke(this, getCurrentInputEditorInfo())
    }

    @Test
    fun tapping_shift_when_auto_capitalised_switches_to_lowercase() {
        val ic = FakeIc(View(ctx), "")
        val ime = TestIme(ic, capSentencesInfo())
        ime.autoCapitalize() // empty field: first letter armed as capital

        ime.onKey(shift())
        ime.onKey(letter('h'))

        assertEquals("h", ic.text.toString())
    }

    @Test
    fun tapping_shift_twice_from_auto_capitalised_gives_one_capital() {
        val ic = FakeIc(View(ctx), "")
        val ime = TestIme(ic, capSentencesInfo())
        ime.autoCapitalize()

        ime.onKey(shift()) // off
        ime.onKey(shift()) // one-shot capital
        ime.onKey(letter('h'))
        ime.onKey(letter('i'))

        assertEquals("Hi", ic.text.toString())
    }

    @Test
    fun manual_shift_tapped_twice_still_locks_caps() {
        val ic = FakeIc(View(ctx), "ok ")
        val ime = TestIme(ic, null)

        ime.onKey(shift())
        ime.onKey(shift())
        ime.onKey(letter('h'))
        ime.onKey(letter('i'))

        assertEquals("ok HI", ic.text.toString())
    }
}
