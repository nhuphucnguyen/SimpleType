package dev.phucngu.simpletype.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.phucngu.simpletype.R

/** What the Enter key should do in the focused field. */
sealed interface EnterAction {
    /** Glyph drawn on the Enter key, so the user can see what it will do. */
    val iconRes: Int get() = R.drawable.ic_kb_enter

    /** Fire the field's editor action (search, send, go, next, done...). */
    data class Perform(val actionId: Int) : EnterAction {
        override val iconRes: Int get() = when (actionId) {
            EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_kb_search
            EditorInfo.IME_ACTION_SEND -> R.drawable.ic_kb_send
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_kb_arrow_forward
            EditorInfo.IME_ACTION_PREVIOUS -> R.drawable.ic_kb_arrow_back
            EditorInfo.IME_ACTION_DONE -> R.drawable.ic_kb_done
            else -> R.drawable.ic_kb_enter
        }
    }
    /** Commit a literal newline. */
    data object Newline : EnterAction
    /** Send a KEYCODE_ENTER key event and let the editor decide (web forms, terminals). */
    data object KeyEvent : EnterAction

    companion object {
        fun resolve(info: EditorInfo?): EnterAction = resolve(info?.imeOptions ?: 0, info?.inputType ?: 0)

        fun resolve(imeOptions: Int, inputType: Int): EnterAction {
            val action = imeOptions and EditorInfo.IME_MASK_ACTION
            val noEnterAction = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
            val multiline = inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
            return when {
                noEnterAction -> Newline
                action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED -> Perform(action)
                multiline -> Newline
                else -> KeyEvent
            }
        }
    }
}
