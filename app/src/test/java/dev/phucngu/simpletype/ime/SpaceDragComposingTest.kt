package dev.phucngu.simpletype.ime

import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import dev.phucngu.simpletype.voice.VoiceLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * While the space bar drags the cursor, the word under the caret must not be picked up as Telex
 * composing text (which the editor underlines). Pick it up once the finger lifts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpaceDragComposingTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class FakeIc(view: View) : BaseInputConnection(view, false) {
        val regions = mutableListOf<Pair<Int, Int>>()
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = "xin chao"
        override fun setComposingRegion(start: Int, end: Int): Boolean {
            regions += start to end; return true
        }
    }

    private class TestIme(private val ic: InputConnection) : SimpleTypeIME() {
        init {
            SimpleTypeIME::class.java.getDeclaredField("language").apply { isAccessible = true }
                .set(this, VoiceLanguage.VIETNAMESE)
        }
        override fun getCurrentInputConnection(): InputConnection = ic
        override fun getCurrentInputEditorInfo(): EditorInfo =
            EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        override fun getMainExecutor(): java.util.concurrent.Executor =
            java.util.concurrent.Executor { it.run() }
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)

    @Test
    fun no_composing_region_while_dragging_then_picked_up_on_release() {
        val ic = FakeIc(View(ctx))
        val ime = TestIme(ic)

        ime.onSpaceCursorStart()
        ime.onUpdateSelection(8, 8, 7, 7, -1, -1)
        idle()
        ime.onUpdateSelection(7, 7, 8, 8, -1, -1)
        idle()
        assertTrue("no underline while dragging, got ${ic.regions}", ic.regions.isEmpty())

        ime.onSpaceCursorEnd()
        idle()
        assertEquals(listOf(4 to 8), ic.regions)
    }
}
