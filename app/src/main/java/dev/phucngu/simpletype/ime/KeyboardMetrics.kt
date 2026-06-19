package dev.phucngu.simpletype.ime

import android.content.SharedPreferences

/**
 * User-adjustable keyboard sizing, in dp: the height of each key row and the gaps between keys
 * (horizontal and vertical, controlled separately). Values are clamped to sane ranges in [of] so
 * a bad preference can never produce an unusable keyboard. Pure data, unit-testable.
 */
data class KeyboardMetrics(
    val rowHeightDp: Float,
    val gapHorizontalDp: Float,
    val gapVerticalDp: Float,
    /** Extra space below the bottom row (on top of the nav-bar inset) to lift keys into reach. */
    val bottomPaddingDp: Float,
    /** Show the corner digit hint on the top QWERTY row and enable swipe-down-to-type-a-number. */
    val showNumberRow: Boolean,
    /** Show a dedicated row of number keys (1-0) above the main QWERTY layout. */
    val showDedicatedNumberRow: Boolean,
    /**
     * Show a common-symbol hint in the corner of every letter key, swipe-down to type it. Mutually
     * exclusive with [showNumberRow]: enabling this forces the number hints off (see [of]).
     */
    val showSymbolHints: Boolean,
) {
    /**
     * Whether the corner number hints should actually be drawn. The [showNumberRow] preference is
     * kept as the user set it, but a dedicated number row makes the hints redundant, so they are
     * hidden while it is shown (and reappear when it is turned off).
     */
    val numberHintsVisible: Boolean get() = showNumberRow && !showDedicatedNumberRow

    companion object {
        /** Defaults follow the M3 Expressive redesign's clean keyboard preview. */
        val DEFAULT = KeyboardMetrics(
            rowHeightDp = 52f, gapHorizontalDp = 6f, gapVerticalDp = 6f,
            bottomPaddingDp = 12f, showNumberRow = false, showDedicatedNumberRow = false,
            showSymbolHints = false,
        )

        const val ROW_HEIGHT_MIN = 44f
        const val ROW_HEIGHT_MAX = 76f
        const val GAP_MIN = 2f
        const val GAP_MAX = 14f
        const val BOTTOM_PAD_MIN = 0f
        const val BOTTOM_PAD_MAX = 64f

        /** Build clamped metrics from raw (possibly out-of-range) values. */
        fun of(
            rowHeightDp: Float,
            gapHorizontalDp: Float,
            gapVerticalDp: Float,
            bottomPaddingDp: Float = DEFAULT.bottomPaddingDp,
            showNumberRow: Boolean = DEFAULT.showNumberRow,
            showDedicatedNumberRow: Boolean = DEFAULT.showDedicatedNumberRow,
            showSymbolHints: Boolean = DEFAULT.showSymbolHints,
        ) = KeyboardMetrics(
            rowHeightDp.coerceIn(ROW_HEIGHT_MIN, ROW_HEIGHT_MAX),
            gapHorizontalDp.coerceIn(GAP_MIN, GAP_MAX),
            gapVerticalDp.coerceIn(GAP_MIN, GAP_MAX),
            bottomPaddingDp.coerceIn(BOTTOM_PAD_MIN, BOTTOM_PAD_MAX),
            // Symbol hints take over the corner/swipe slot, so they force the number row off.
            showNumberRow = showNumberRow && !showSymbolHints,
            showDedicatedNumberRow = showDedicatedNumberRow,
            showSymbolHints = showSymbolHints,
        )

        private const val KEY_ROW = "kb_row_height_dp"
        private const val KEY_GAP_H = "kb_gap_h_dp"
        private const val KEY_GAP_V = "kb_gap_v_dp"
        private const val KEY_BOTTOM_PAD = "kb_bottom_pad_dp"
        private const val KEY_NUMBER_ROW = "kb_number_row"
        private const val KEY_DEDICATED_NUMBER_ROW = "kb_dedicated_number_row"
        private const val KEY_SYMBOL_HINTS = "kb_symbol_hints"

        fun load(prefs: SharedPreferences): KeyboardMetrics = of(
            prefs.getFloat(KEY_ROW, DEFAULT.rowHeightDp),
            prefs.getFloat(KEY_GAP_H, DEFAULT.gapHorizontalDp),
            prefs.getFloat(KEY_GAP_V, DEFAULT.gapVerticalDp),
            prefs.getFloat(KEY_BOTTOM_PAD, DEFAULT.bottomPaddingDp),
            prefs.getBoolean(KEY_NUMBER_ROW, DEFAULT.showNumberRow),
            prefs.getBoolean(KEY_DEDICATED_NUMBER_ROW, DEFAULT.showDedicatedNumberRow),
            prefs.getBoolean(KEY_SYMBOL_HINTS, DEFAULT.showSymbolHints),
        )

        fun save(prefs: SharedPreferences, metrics: KeyboardMetrics) {
            prefs.edit()
                .putFloat(KEY_ROW, metrics.rowHeightDp)
                .putFloat(KEY_GAP_H, metrics.gapHorizontalDp)
                .putFloat(KEY_GAP_V, metrics.gapVerticalDp)
                .putFloat(KEY_BOTTOM_PAD, metrics.bottomPaddingDp)
                .putBoolean(KEY_NUMBER_ROW, metrics.showNumberRow)
                .putBoolean(KEY_DEDICATED_NUMBER_ROW, metrics.showDedicatedNumberRow)
                .putBoolean(KEY_SYMBOL_HINTS, metrics.showSymbolHints)
                .apply()
        }
    }
}
