package dev.phucngu.simpletype.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.phucngu.simpletype.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [EnterAction.resolve]: what the Enter key does for a given field. Mirrors AOSP LatinIME —
 * only [EditorInfo.IME_FLAG_NO_ENTER_ACTION] suppresses a declared action; the multi-line flag
 * alone does not (Compose and Flutter fields set it without the no-enter flag).
 */
class EnterActionTest {

    private val text = InputType.TYPE_CLASS_TEXT
    private val multiLine = text or InputType.TYPE_TEXT_FLAG_MULTI_LINE

    @Test fun singleLineSearchFieldPerformsSearch() {
        assertEquals(
            EnterAction.Perform(EditorInfo.IME_ACTION_SEARCH),
            EnterAction.resolve(EditorInfo.IME_ACTION_SEARCH, text),
        )
    }

    @Test fun multiLineFieldWithDeclaredActionPerformsIt() {
        // Compose/Flutter text field with imeAction = Search/Done but not singleLine.
        assertEquals(
            EnterAction.Perform(EditorInfo.IME_ACTION_SEARCH),
            EnterAction.resolve(EditorInfo.IME_ACTION_SEARCH, multiLine),
        )
        assertEquals(
            EnterAction.Perform(EditorInfo.IME_ACTION_DONE),
            EnterAction.resolve(EditorInfo.IME_ACTION_DONE, multiLine),
        )
    }

    @Test fun noEnterActionFlagInsertsNewline() {
        // What TextView sets on every multi-line EditText (e.g. a messaging compose box).
        val opts = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertEquals(EnterAction.Newline, EnterAction.resolve(opts, multiLine))
    }

    @Test fun multiLineWithoutActionInsertsNewline() {
        assertEquals(EnterAction.Newline, EnterAction.resolve(EditorInfo.IME_ACTION_NONE, multiLine))
        assertEquals(EnterAction.Newline, EnterAction.resolve(EditorInfo.IME_ACTION_UNSPECIFIED, multiLine))
    }

    @Test fun singleLineWithoutActionSendsEnterKey() {
        // Web forms, RN/Flutter fields: they listen for KEYCODE_ENTER, not a committed "\n".
        assertEquals(EnterAction.KeyEvent, EnterAction.resolve(EditorInfo.IME_ACTION_NONE, text))
        assertEquals(EnterAction.KeyEvent, EnterAction.resolve(EditorInfo.IME_ACTION_UNSPECIFIED, text))
    }

    @Test fun nullTypeFieldSendsEnterKey() {
        // Terminals and raw views report TYPE_NULL and expect key events.
        assertEquals(EnterAction.KeyEvent, EnterAction.resolve(EditorInfo.IME_ACTION_UNSPECIFIED, InputType.TYPE_NULL))
    }

    @Test fun iconMatchesAction() {
        fun icon(action: Int) = EnterAction.Perform(action).iconRes
        assertEquals(R.drawable.ic_kb_search, icon(EditorInfo.IME_ACTION_SEARCH))
        assertEquals(R.drawable.ic_kb_send, icon(EditorInfo.IME_ACTION_SEND))
        assertEquals(R.drawable.ic_kb_arrow_forward, icon(EditorInfo.IME_ACTION_GO))
        assertEquals(R.drawable.ic_kb_arrow_forward, icon(EditorInfo.IME_ACTION_NEXT))
        assertEquals(R.drawable.ic_kb_arrow_back, icon(EditorInfo.IME_ACTION_PREVIOUS))
        assertEquals(R.drawable.ic_kb_done, icon(EditorInfo.IME_ACTION_DONE))
    }

    @Test fun newlineAndKeyEventKeepReturnIcon() {
        assertEquals(R.drawable.ic_kb_enter, EnterAction.Newline.iconRes)
        assertEquals(R.drawable.ic_kb_enter, EnterAction.KeyEvent.iconRes)
    }
}
