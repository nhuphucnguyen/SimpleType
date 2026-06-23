package dev.phucngu.simpletype.ime.keyboard.layout

import dev.phucngu.simpletype.R
import dev.phucngu.simpletype.ime.keyboard.model.Key
import dev.phucngu.simpletype.ime.keyboard.model.KeyCode
import dev.phucngu.simpletype.ime.keyboard.model.KeyStyle
import dev.phucngu.simpletype.ime.keyboard.model.Keyboard
import dev.phucngu.simpletype.ime.keyboard.model.KeyboardRow

/** The two symbol pages and their navigation keys. */
object SymbolKeyboardLayout {
    fun primary(): Keyboard = Keyboard(
        listOf(
            lettersRow("1234567890"),
            KeyboardRow("@#đ_&-+()/".map(::letter)),
            row(
                Key(KeyCode.SYMBOLS_ALT, "=\\<", weight = 1.5f, style = KeyStyle.SPECIAL),
                *"*\"':;!?".map(::letter).toTypedArray(),
                Key(KeyCode.DELETE, "Delete", weight = 1.5f, style = KeyStyle.SPECIAL,
                    repeatable = true, iconRes = R.drawable.ic_kb_backspace),
            ),
            bottomRow(alternate = false),
        ),
    )

    fun alternate(): Keyboard = Keyboard(
        listOf(
            KeyboardRow("~`|•√π÷×§∆".map(::letter)),
            KeyboardRow("£€\$¢^°={}\\".map(::letter)),
            row(
                Key(KeyCode.SYMBOLS, "?123", weight = 1.5f, style = KeyStyle.SPECIAL),
                *"%©®™✓[]".map(::letter).toTypedArray(),
                Key(KeyCode.DELETE, "Delete", weight = 1.5f, style = KeyStyle.SPECIAL,
                    repeatable = true, iconRes = R.drawable.ic_kb_backspace),
            ),
            bottomRow(alternate = true),
        ),
    )

    private fun bottomRow(alternate: Boolean): KeyboardRow {
        val left = if (alternate) letter('<').copy(style = KeyStyle.SPECIAL)
        else letter(',').copy(style = KeyStyle.SPECIAL, longPressCode = KeyCode.EMOJI)
        val right = if (alternate) letter('>').copy(style = KeyStyle.SPECIAL)
        else letter('.').copy(style = KeyStyle.SPECIAL)
        return row(
            Key(KeyCode.ALPHA, "ABC", weight = 1.5f, style = KeyStyle.SPECIAL),
            left,
            Key(KeyCode.SPACE, "", weight = 5f),
            right,
            Key(KeyCode.ENTER, "Enter", weight = 1.5f, style = KeyStyle.SPECIAL,
                iconRes = R.drawable.ic_kb_enter),
        )
    }
}
