package dev.phucngu.simpletype.ime.keyboard.layout

import dev.phucngu.simpletype.R
import dev.phucngu.simpletype.ime.keyboard.model.Key
import dev.phucngu.simpletype.ime.keyboard.model.KeyCode
import dev.phucngu.simpletype.ime.keyboard.model.KeyStyle
import dev.phucngu.simpletype.ime.keyboard.model.Keyboard
import dev.phucngu.simpletype.ime.keyboard.model.KeyboardRow

/** Standard QWERTY layout. Vietnamese reuses it and applies Telex composition. */
object QwertyKeyboardLayout {
    fun create(showDedicatedNumberRow: Boolean = false): Keyboard = Keyboard(
        mutableListOf<KeyboardRow>().apply {
            if (showDedicatedNumberRow) add(lettersRow("1234567890"))
            add(hintRow("qwertyuiop", numbers = "1234567890", symbols = "%`|=[]<>{}"))
            add(hintRow("asdfghjkl", symbols = "@#đ_&-+()", sideWeight = 0.5f))
            val bottomLetters = "zxcvbnm".mapIndexed { index, char ->
                letter(char).copy(symbolHint = "*\"':;!?"[index])
            }
            add(row(
                Key(KeyCode.SHIFT, "Shift", weight = 1.5f, style = KeyStyle.SPECIAL,
                    iconRes = R.drawable.ic_kb_shift),
                *bottomLetters.toTypedArray(),
                Key(KeyCode.DELETE, "Delete", weight = 1.5f, style = KeyStyle.SPECIAL,
                    repeatable = true, iconRes = R.drawable.ic_kb_backspace),
            ))
            add(bottomRow())
        },
    )

    private fun hintRow(
        chars: String,
        numbers: String? = null,
        symbols: String? = null,
        sideWeight: Float = 0f,
    ) = KeyboardRow(
        chars.mapIndexed { index, char ->
            letter(char).copy(numberHint = numbers?.get(index), symbolHint = symbols?.get(index))
        },
        sideWeight,
    )

    private fun bottomRow() = row(
        Key(KeyCode.SYMBOLS, "?123", weight = 1.5f, style = KeyStyle.SPECIAL),
        Key(','.code, ",", style = KeyStyle.SPECIAL, longPressCode = KeyCode.EMOJI),
        Key(KeyCode.SPACE, "", weight = 5f),
        Key('.'.code, ".", style = KeyStyle.SPECIAL),
        Key(KeyCode.ENTER, "Enter", weight = 1.5f, style = KeyStyle.SPECIAL,
            iconRes = R.drawable.ic_kb_enter),
    )
}
