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
    const val EMOJI = -9     // emoji key (panel TBD)
    const val SPACE = ' '.code
    // Note: language switching is the system globe (IME subtype) and voice is the toolbar
    // mic button, so neither has a grid key / KeyCode here.
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
    /** Action fired on long-press (e.g. comma → emoji), or null if the key has no long-press. */
    val longPressCode: Int? = null,
    /**
     * Digit shown small in the key's top corner and committed on a downward swipe (the top
     * QWERTY row doubles as a number row: q→1 … p→0). Null when the key has no number shortcut.
     */
    val numberHint: Char? = null,
) {
    val isPrintable: Boolean get() = code >= 32
}

/**
 * A row of keys. [sideWeight] is empty padding (in key-weight units) added to each end so a row
 * with fewer keys can be indented to keep every key the same width as the full rows (e.g. the
 * 9-key asdf row is indented by half a key each side to match the 10-key qwerty row).
 */
data class KeyboardRow(val keys: List<Key>, val sideWeight: Float = 0f)

data class Keyboard(val rows: List<KeyboardRow>)

/** Static layout definitions. Vietnamese reuses the QWERTY layout (Telex types diacritics). */
object KeyboardLayouts {

    private fun row(vararg keys: Key) = KeyboardRow(keys.toList())
    private fun letter(c: Char) = Key(c.code, c.toString())

    private fun lettersRow(chars: String, sideWeight: Float = 0f): KeyboardRow =
        KeyboardRow(chars.map { letter(it) }, sideWeight)

    /** Top letter row whose ten keys double as a number row (q→1 … p→0) via corner hint + swipe-down. */
    private fun numberHintRow(chars: String, digits: String): KeyboardRow =
        KeyboardRow(chars.mapIndexed { i, c -> letter(c).copy(numberHint = digits[i]) })

    /** Standard QWERTY with shift, delete, symbol toggle, globe, mic, space and enter. */
    fun qwerty(): Keyboard = Keyboard(
        listOf(
            numberHintRow("qwertyuiop", "1234567890"),
            // Indent the 9-key middle row by half a key each side so its keys match the 10-key rows.
            lettersRow("asdfghjkl", sideWeight = 0.5f),
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

    // Mic lives in the top toolbar and the globe in the bottom strip, so the bottom row mirrors
    // Gboard: ?123 · , · space · . · enter. Symmetric (2.5 weight each side) so space is centered;
    // comma is a direct key and long-press opens the emoji panel.
    private fun bottomRow(): KeyboardRow = row(
        Key(KeyCode.SYMBOLS, "?123", weight = 1.5f, style = KeyStyle.SPECIAL),
        Key(','.code, ",", style = KeyStyle.SPECIAL, longPressCode = KeyCode.EMOJI),
        Key(KeyCode.SPACE, "", weight = 5f),
        Key('.'.code, ".", style = KeyStyle.SPECIAL),
        Key(KeyCode.ENTER, "Enter", weight = 1.5f, style = KeyStyle.SPECIAL,
            iconRes = R.drawable.ic_kb_enter),
    )

    /** Symbols page 1: numbers and common punctuation (Gboard's ?123 page). */
    fun symbols(): Keyboard = Keyboard(
        listOf(
            lettersRow("1234567890"),
            KeyboardRow("@#đ_&-+()/".map { Key(it.code, it.toString()) }),
            row(
                Key(KeyCode.SYMBOLS_ALT, "=\\<", weight = 1.5f, style = KeyStyle.SPECIAL),
                *"*\"':;!?".map { Key(it.code, it.toString()) }.toTypedArray(),
                Key(KeyCode.DELETE, "Delete", weight = 1.5f, style = KeyStyle.SPECIAL,
                    repeatable = true, iconRes = R.drawable.ic_kb_backspace),
            ),
            symbolsBottomRow(altPage = false),
        )
    )

    /** Symbols page 2: maths / currency / brackets (Gboard's =\< page). */
    fun symbolsAlt(): Keyboard = Keyboard(
        listOf(
            KeyboardRow("~`|•√π÷×§∆".map { Key(it.code, it.toString()) }),
            KeyboardRow("£€\$¢^°={}\\".map { Key(it.code, it.toString()) }),
            row(
                Key(KeyCode.SYMBOLS, "?123", weight = 1.5f, style = KeyStyle.SPECIAL),
                *"%©®™✓[]".map { Key(it.code, it.toString()) }.toTypedArray(),
                Key(KeyCode.DELETE, "Delete", weight = 1.5f, style = KeyStyle.SPECIAL,
                    repeatable = true, iconRes = R.drawable.ic_kb_backspace),
            ),
            symbolsBottomRow(altPage = true),
        )
    )

    /**
     * Shared bottom row for the symbol pages. Page 1 flanks the space with `,` (emoji on long-press)
     * and `.`; page 2 swaps those for `<` and `>`, mirroring Gboard. Symmetric so space stays centered.
     */
    private fun symbolsBottomRow(altPage: Boolean): KeyboardRow {
        val left = if (altPage) Key('<'.code, "<", style = KeyStyle.SPECIAL)
        else Key(','.code, ",", style = KeyStyle.SPECIAL, longPressCode = KeyCode.EMOJI)
        val right = if (altPage) Key('>'.code, ">", style = KeyStyle.SPECIAL)
        else Key('.'.code, ".", style = KeyStyle.SPECIAL)
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
