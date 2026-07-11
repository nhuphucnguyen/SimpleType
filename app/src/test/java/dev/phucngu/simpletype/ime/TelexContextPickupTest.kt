package dev.phucngu.simpletype.ime

import dev.phucngu.simpletype.ime.keyboard.model.Key

import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import dev.phucngu.simpletype.text.TelexEngine
import dev.phucngu.simpletype.voice.VoiceLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelexContextPickupTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class FakeIc(view: View) : BaseInputConnection(view, false) {
        var text = ""
        var composing = ""
        var selStart = 0
        var selEnd = 0
        var textBeforeCursorCalls = 0

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
            textBeforeCursorCalls++
            return text.substring(0, selStart).takeLast(n)
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composing = text?.toString() ?: ""
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val s = text?.toString() ?: ""
            this.text = this.text.substring(0, selStart) + s + this.text.substring(selEnd)
            selStart += s.length
            selEnd = selStart
            composing = ""
            return true
        }

        override fun finishComposingText(): Boolean {
            if (composing.isNotEmpty()) {
                text = text.substring(0, selStart) + composing + text.substring(selEnd)
                selStart += composing.length
                selEnd = selStart
                composing = ""
            }
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            // Simplification: we don't fully track it but we'll use it to "pick up"
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

    private fun getComposingText(ime: SimpleTypeIME): String {
        val engine = SimpleTypeIME::class.java.getDeclaredField("telex")
            .apply { isAccessible = true }.get(ime) as TelexEngine
        return engine.composing
    }

    @Test
    fun picking_up_context_on_cursor_move() {
        val ic = FakeIc(View(ctx))
        ic.text = "cong hoa xa"
        ic.selStart = 4 // end of "cong"
        ic.selEnd = 4
        
        val ime = TestIme(ic)

        // Simulate moving cursor to end of "cong"
        // In a real scenario, this might be triggered by onUpdateSelection
        ime.onUpdateSelection(11, 11, 4, 4, -1, -1)
        // Context pickup is debounced so bursts of selection updates collapse into
        // one re-sync; advance the main looper past the debounce window.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)

        // Now type 'o'
        ime.onKey(Key('o'.code, "o"))

        // Expect it to have picked up "cong" and turned it into "công"
        assertEquals("công", ic.composing)
        
        // Type 'j'
        ime.onKey(Key('j'.code, "j"))
        assertEquals("cộng", ic.composing)
    }

    /**
     * Every commit echoes back a burst of selection updates. Each pickup used to run a
     * blocking getTextBeforeCursor per update, starving touch events during the next
     * glide gesture; the burst must collapse into a single deferred pickup.
     */
    @Test
    fun update_bursts_collapse_into_one_pickup() {
        val ic = FakeIc(View(ctx))
        ic.text = "cong hoa xa"
        ic.selStart = 4
        ic.selEnd = 4

        val ime = TestIme(ic)

        repeat(5) { ime.onUpdateSelection(11, 11, 4, 4, -1, -1) }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertEquals(1, ic.textBeforeCursorCalls)
    }
}
