package dev.phucngu.simpletype.ime

import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NumericKeyboardInputTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class RecordingInputConnection(view: View) : BaseInputConnection(view, false) {
        val committed = StringBuilder()

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            committed.append(text)
            return true
        }
    }

    private class TestIme(private val inputConnection: InputConnection) : SimpleTypeIME() {
        override fun getCurrentInputConnection(): InputConnection = inputConnection
    }

    @Test fun double_zero_key_commits_two_zeroes() {
        val inputConnection = RecordingInputConnection(View(context))
        val ime = TestIme(inputConnection)

        ime.onKey(Key(KeyCode.DOUBLE_ZERO, "00"))

        assertEquals("00", inputConnection.committed.toString())
    }
}
