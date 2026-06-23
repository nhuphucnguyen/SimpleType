package dev.phucngu.simpletype.ime.keyboard.layout

import dev.phucngu.simpletype.R
import dev.phucngu.simpletype.ime.keyboard.model.Key
import dev.phucngu.simpletype.ime.keyboard.model.KeyCode
import dev.phucngu.simpletype.ime.keyboard.model.KeyStyle
import dev.phucngu.simpletype.ime.keyboard.model.Keyboard

/** Calculator-style keypad for numeric editor fields. */
object NumericKeyboardLayout {
    fun create(): Keyboard = Keyboard(
        rows = listOf(
            row(
                *"123".map(::letter).toTypedArray(),
                Key(KeyCode.DELETE, "Delete", style = KeyStyle.SPECIAL, repeatable = true,
                    iconRes = R.drawable.ic_kb_backspace, rowSpan = 2),
            ),
            lettersRow("456"),
            row(
                *"789".map(::letter).toTypedArray(),
                Key(KeyCode.ENTER, "Enter", style = KeyStyle.SPECIAL,
                    iconRes = R.drawable.ic_kb_enter, rowSpan = 2),
            ),
            row(Key(KeyCode.DOUBLE_ZERO, "00"), letter('0'), letter('.')),
        ),
        fixedColumns = 4,
    )
}
