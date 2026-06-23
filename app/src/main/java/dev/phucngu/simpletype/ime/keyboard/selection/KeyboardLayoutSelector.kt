package dev.phucngu.simpletype.ime.keyboard.selection

import android.text.InputType

enum class KeyboardLayoutType { ALPHA, SYMBOLS, SYMBOLS_ALT, NUMERIC }

/** Maps Android editor metadata to the initial keyboard layout. */
object KeyboardLayoutSelector {
    fun forInputType(inputType: Int): KeyboardLayoutType =
        when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> KeyboardLayoutType.NUMERIC
            InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> KeyboardLayoutType.SYMBOLS
            else -> KeyboardLayoutType.ALPHA
        }
}
