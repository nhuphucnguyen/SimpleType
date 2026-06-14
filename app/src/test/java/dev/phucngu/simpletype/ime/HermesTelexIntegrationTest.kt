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
 * End-to-end Telex check through [SimpleTypeIME.onKey], not just the engine in isolation: typing
 * "herrmes" must commit "hermes" (the second `r` escapes the hỏi tone). The engine handles this, but
 * on a real device [SimpleTypeIME.onUpdateSelection] fires between keystrokes and can finish/reload
 * the composing word, so this test drives that churn too — including the worst case where the editor
 * reports no composing region (candidatesEnd = -1), which forces a finish+reload on every keystroke.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HermesTelexIntegrationTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** A faithful-enough editor: tracks the visible text, the composing span, and the cursor. */
    private class FakeIc(view: View) : BaseInputConnection(view, false) {
        val sb = StringBuilder()
        var cursor = 0
        var spanStart = -1
        var spanEnd = -1

        val visible: String get() = sb.toString()

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
            sb.substring(0, cursor).takeLast(n)

        override fun getSelectedText(flags: Int): CharSequence? = null

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val t = text?.toString() ?: ""
            if (spanStart < 0) { spanStart = cursor; spanEnd = cursor }
            sb.replace(spanStart, spanEnd, t)
            spanEnd = spanStart + t.length
            cursor = spanEnd
            return true
        }

        override fun finishComposingText(): Boolean {
            spanStart = -1; spanEnd = -1
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val t = text?.toString() ?: ""
            val s = if (spanStart >= 0) spanStart else cursor
            val e = if (spanStart >= 0) spanEnd else cursor
            sb.replace(s, e, t)
            cursor = s + t.length
            spanStart = -1; spanEnd = -1
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            spanStart = start; spanEnd = end
            cursor = end
            return true
        }
    }

    private class TestIme(kv: LatinKeyboardView, private val ic: InputConnection) : SimpleTypeIME() {
        init {
            SimpleTypeIME::class.java.getDeclaredField("keyboardView")
                .apply { isAccessible = true }.set(this, kv)
            SimpleTypeIME::class.java.getDeclaredField("language")
                .apply { isAccessible = true }.set(this, VoiceLanguage.VIETNAMESE)
        }
        override fun getCurrentInputConnection(): InputConnection = ic
        override fun getCurrentInputEditorInfo(): EditorInfo? = null
    }

    /** Type a word letter-by-letter, firing onUpdateSelection after each key like the framework. */
    private fun typeWord(word: String, reportComposingRegion: Boolean): String {
        val ic = FakeIc(View(ctx))
        val ime = TestIme(LatinKeyboardView(ctx), ic)
        var old = 0
        for (c in word) {
            ime.onKey(Key(c.code, c.toString()))
            val candStart = if (reportComposingRegion) ic.spanStart else -1
            val candEnd = if (reportComposingRegion) ic.spanEnd else -1
            ime.onUpdateSelection(old, old, ic.cursor, ic.cursor, candStart, candEnd)
            old = ic.cursor
        }
        // Commit the word (as a trailing space / focus loss would).
        ic.finishComposingText()
        return ic.visible
    }

    @Test fun herrmes_commits_hermes_well_behaved_editor() =
        assertEquals("hermes", typeWord("herrmes", reportComposingRegion = true))

    @Test fun herrmes_commits_hermes_editor_without_composing_region() =
        assertEquals("hermes", typeWord("herrmes", reportComposingRegion = false))

    // "error" (typed e,r,r,r,o,r): the final r is a real coda past the toned 'e', not a stale escape.
    @Test fun error_commits_error_well_behaved_editor() =
        assertEquals("error", typeWord("errror", reportComposingRegion = true))

    @Test fun error_commits_error_editor_without_composing_region() =
        assertEquals("error", typeWord("errror", reportComposingRegion = false))
}
