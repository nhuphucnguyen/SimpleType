package dev.phucngu.simpletype.ime.keyboard.model

import androidx.annotation.DrawableRes

/** Negative codes identify control keys. Printable keys use their Unicode code point. */
object KeyCode {
    const val SHIFT = -1
    const val DELETE = -2
    const val ENTER = -3
    const val SYMBOLS = -4
    const val ALPHA = -5
    const val SYMBOLS_ALT = -6
    const val EMOJI = -9
    const val DOUBLE_ZERO = -10
    const val SPACE = ' '.code
}

enum class KeyStyle { NORMAL, SPECIAL, ACCENT }

data class Key(
    val code: Int,
    val label: String,
    val weight: Float = 1f,
    val style: KeyStyle = KeyStyle.NORMAL,
    val repeatable: Boolean = false,
    @param:DrawableRes val iconRes: Int? = null,
    val longPressCode: Int? = null,
    val numberHint: Char? = null,
    val symbolHint: Char? = null,
    /** Number of rows occupied by this key in a fixed-column keyboard. */
    val rowSpan: Int = 1,
) {
    val isPrintable: Boolean get() = code >= 32
}

data class KeyboardRow(val keys: List<Key>, val sideWeight: Float = 0f)

data class Keyboard(
    val rows: List<KeyboardRow>,
    /** Enables grid placement and vertically spanning keys when non-null. */
    val fixedColumns: Int? = null,
)
