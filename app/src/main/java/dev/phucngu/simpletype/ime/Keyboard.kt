package dev.phucngu.simpletype.ime

import androidx.annotation.DrawableRes
import dev.phucngu.simpletype.R

/**
 * Data model for a keyboard layout: a list of rows, each a list of [Key]s.
 *
 * Key behaviour is identified by [Key.code]. Printable keys carry the Unicode code
 * point of their lowercase character; control keys use the negative [KeyCode] constants.
 */

/** Negative codes identify non-printing control keys. Printable keys use their char code. */
object KeyCode {
    const val SHIFT = -1
    const val DELETE = -2
    const val ENTER = -3
    const val SYMBOLS = -4   // letters → symbols page 1
    const val ALPHA = -5     // symbols → letters
    const val SYMBOLS_ALT = -6 // symbols page 1 ↔ page 2
    const val LANGUAGE = -7  // globe: cycle EN ⇄ VI
    const val MIC = -8       // voice typing
    const val SPACE = ' '.code
}

enum class KeyStyle { NORMAL, SPECIAL, ACCENT }

data class Key(
    val code: Int,
    val label: String,
    /** Relative width within its row; most keys are 1f. */
    val weight: Float = 1f,
    val style: KeyStyle = KeyStyle.NORMAL,
    val repeatable: Boolean = false,
    /** When set, the key draws this monochrome icon instead of [label]. */
    @param:DrawableRes val iconRes: Int? = null,
) {
    val isPrintable: Boolean get() = code >= 32
}

data class KeyboardRow(val keys: List<Key>)

data class Keyboard(val rows: List<KeyboardRow>)

/** Static layout definitions. Vietnamese reuses the QWERTY layout (Telex types diacritics). */
object KeyboardLayouts {

    private fun row(vararg keys: Key) = KeyboardRow(keys.toList())
    private fun letter(c: Char) = Key(c.code, c.toString())

    private fun lettersRow(chars: String): KeyboardRow =
        KeyboardRow(chars.map { letter(it) })

    /** Standard QWERTY with shift, delete, symbol toggle, globe, mic, space and enter. */
    fun qwerty(): Keyboard = Keyboard(
        listOf(
            lettersRow("qwertyuiop"),
            // Indent the middle row by giving the edges half-gap padding via weights.
            lettersRow("asdfghjkl"),
            row(
                Key(KeyCode.SHIFT, "Shift", weight = 1.5f, style = KeyStyle.SPECIAL,
                    iconRes = R.drawable.ic_kb_shift),
                letter('z'), letter('x'), letter('c'), letter('v'),
                letter('b'), letter('n'), letter('m'),
                Key(KeyCode.DELETE, "Delete", weight = 1.5f, style = KeyStyle.SPECIAL,
                    repeatable = true, iconRes = R.drawable.ic_kb_backspace),
            ),
            bottomRow(),
        )
    )

    private fun bottomRow(): KeyboardRow = row(
        Key(KeyCode.SYMBOLS, "?123", weight = 1.5f, style = KeyStyle.SPECIAL),
        Key(KeyCode.LANGUAGE, "Language", weight = 1.2f, style = KeyStyle.SPECIAL,
            iconRes = R.drawable.ic_kb_language),
        Key(KeyCode.MIC, "Voice", weight = 1.2f, style = KeyStyle.SPECIAL,
            iconRes = R.drawable.ic_kb_mic),
        Key(','.code, ","),
        Key(KeyCode.SPACE, "", weight = 4f),
        Key('.'.code, "."),
        Key(KeyCode.ENTER, "Enter", weight = 1.8f, style = KeyStyle.ACCENT,
            iconRes = R.drawable.ic_kb_enter),
    )

    /** Symbols page 1: numbers and common punctuation. */
    fun symbols(): Keyboard = Keyboard(
        listOf(
            lettersRow("1234567890"),
            KeyboardRow("@#\$_&-+()/".map { Key(it.code, it.toString()) }),
            row(
                Key(KeyCode.SYMBOLS_ALT, "=\\<", weight = 1.5f, style = KeyStyle.SPECIAL),
                *"*\"':;!?".map { Key(it.code, it.toString()) }.toTypedArray(),
                Key(KeyCode.DELETE, "Delete", weight = 1.5f, style = KeyStyle.SPECIAL,
                    repeatable = true, iconRes = R.drawable.ic_kb_backspace),
            ),
            symbolsBottomRow(),
        )
    )

    /** Symbols page 2: maths / brackets / less common marks. */
    fun symbolsAlt(): Keyboard = Keyboard(
        listOf(
            KeyboardRow("~`|•√π÷×¶∆".map { Key(it.code, it.toString()) }),
            KeyboardRow("£¢€¥^°={}".map { Key(it.code, it.toString()) }),
            row(
                Key(KeyCode.SYMBOLS, "?123", weight = 1.5f, style = KeyStyle.SPECIAL),
                *"\\©®™%[]".map { Key(it.code, it.toString()) }.toTypedArray(),
                Key(KeyCode.DELETE, "Delete", weight = 1.5f, style = KeyStyle.SPECIAL,
                    repeatable = true, iconRes = R.drawable.ic_kb_backspace),
            ),
            symbolsBottomRow(),
        )
    )

    private fun symbolsBottomRow(): KeyboardRow = row(
        Key(KeyCode.ALPHA, "ABC", weight = 1.5f, style = KeyStyle.SPECIAL),
        Key(KeyCode.LANGUAGE, "Language", weight = 1.2f, style = KeyStyle.SPECIAL,
            iconRes = R.drawable.ic_kb_language),
        Key(','.code, ","),
        Key(KeyCode.SPACE, "", weight = 4f),
        Key('.'.code, "."),
        Key(KeyCode.ENTER, "Enter", weight = 1.8f, style = KeyStyle.ACCENT,
            iconRes = R.drawable.ic_kb_enter),
    )
}
