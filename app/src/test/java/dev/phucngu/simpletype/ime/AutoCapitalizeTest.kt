package dev.phucngu.simpletype.ime

import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [SimpleTypeIME.updateAutoCapitalize]: a period only arms the next-letter shift when it is
 * followed by a space, so URLs like "example.com" are not auto-capitalised mid-word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoCapitalizeTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class FakeIc(view: View, initial: String) : BaseInputConnection(view, false) {
        val text = StringBuilder(initial)
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
            val full = text.toString()
            return full.substring(maxOf(0, full.length - n))
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

    private fun shiftedAfter(before: String): Boolean {
        val ic = FakeIc(View(ctx), before)
        val ime = TestIme(ic, capSentencesInfo())
        val m = SimpleTypeIME::class.java.getDeclaredMethod("updateAutoCapitalize", EditorInfo::class.java)
        m.isAccessible = true
        m.invoke(ime, ime.getCurrentInputEditorInfo())
        val shiftedField = SimpleTypeIME::class.java.getDeclaredField("shifted")
        shiftedField.isAccessible = true
        return shiftedField.getBoolean(ime)
    }

    @Test
    fun period_with_following_space_arms_capitalization() {
        assertTrue(shiftedAfter("Hi. "))
    }

    @Test
    fun period_without_space_does_not_capitalize() {
        // Mid-URL: "example." -> next char must stay lowercase.
        assertFalse(shiftedAfter("e."))
    }

    @Test
    fun question_mark_with_space_arms_capitalization() {
        assertTrue(shiftedAfter("ok? "))
    }
}
