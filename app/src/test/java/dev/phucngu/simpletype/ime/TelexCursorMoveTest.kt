package dev.phucngu.simpletype.ime

import dev.phucngu.simpletype.ime.keyboard.model.Key

import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import dev.phucngu.simpletype.text.TelexEngine
import dev.phucngu.simpletype.voice.VoiceLanguage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Moving the cursor by touch (an external selection change) must end the active Telex composing
 * word, so the next keystroke starts at the new cursor instead of rewriting the old word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelexCursorMoveTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class FakeIc(view: View) : BaseInputConnection(view, false) {
        val events = mutableListOf<String>()
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            events += "compose:$text"; return true
        }
        override fun finishComposingText(): Boolean { events += "finish"; return true }
    }

    private class TestIme(private val ic: InputConnection) : SimpleTypeIME() {
        init {
            SimpleTypeIME::class.java.getDeclaredField("language").apply { isAccessible = true }
                .set(this, VoiceLanguage.VIETNAMESE)
        }
        override fun getCurrentInputConnection(): InputConnection = ic
        override fun getCurrentInputEditorInfo(): EditorInfo? = null
    }

    private fun telexEmpty(ime: SimpleTypeIME): Boolean {
        val engine = SimpleTypeIME::class.java.getDeclaredField("telex")
            .apply { isAccessible = true }.get(ime) as TelexEngine
        return engine.isEmpty
    }

    @Test
    fun tapping_elsewhere_ends_composing() {
        val ic = FakeIc(View(ctx))
        val ime = TestIme(ic)

        ime.onKey(Key('a'.code, "a")) // compose "a" → composing region [0,1], cursor at 1
        assertFalse("engine should be composing", telexEmpty(ime))

        // User taps to move the cursor to position 5; composing region is still [0,1].
        ime.onUpdateSelection(1, 1, 5, 5, 0, 1)
        // The selection re-sync is debounced; advance past the window.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertTrue("composing must be finished after cursor move", telexEmpty(ime))
        assertTrue("should call finishComposingText", ic.events.contains("finish"))
    }

    @Test
    fun our_own_composing_update_does_not_reset() {
        val ic = FakeIc(View(ctx))
        val ime = TestIme(ic)

        ime.onKey(Key('a'.code, "a"))
        // The selection update caused by our own setComposingText: cursor sits at composing end.
        ime.onUpdateSelection(0, 0, 1, 1, 0, 1)
        // Even after the debounce window, our own update must not reset composing.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertFalse("engine should still be composing", telexEmpty(ime))
    }
}
