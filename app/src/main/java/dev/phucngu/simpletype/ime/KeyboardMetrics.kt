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
) {
    companion object {
        /** Defaults mirror the original res/values/dimens.xml values. */
        val DEFAULT = KeyboardMetrics(rowHeightDp = 52f, gapHorizontalDp = 4f, gapVerticalDp = 4f)

        const val ROW_HEIGHT_MIN = 44f
        const val ROW_HEIGHT_MAX = 76f
        const val GAP_MIN = 2f
        const val GAP_MAX = 14f

        /** Build clamped metrics from raw (possibly out-of-range) values. */
        fun of(rowHeightDp: Float, gapHorizontalDp: Float, gapVerticalDp: Float) = KeyboardMetrics(
            rowHeightDp.coerceIn(ROW_HEIGHT_MIN, ROW_HEIGHT_MAX),
            gapHorizontalDp.coerceIn(GAP_MIN, GAP_MAX),
            gapVerticalDp.coerceIn(GAP_MIN, GAP_MAX),
        )

        private const val KEY_ROW = "kb_row_height_dp"
        private const val KEY_GAP_H = "kb_gap_h_dp"
        private const val KEY_GAP_V = "kb_gap_v_dp"

        fun load(prefs: SharedPreferences): KeyboardMetrics = of(
            prefs.getFloat(KEY_ROW, DEFAULT.rowHeightDp),
            prefs.getFloat(KEY_GAP_H, DEFAULT.gapHorizontalDp),
            prefs.getFloat(KEY_GAP_V, DEFAULT.gapVerticalDp),
        )

        fun save(prefs: SharedPreferences, metrics: KeyboardMetrics) {
            prefs.edit()
                .putFloat(KEY_ROW, metrics.rowHeightDp)
                .putFloat(KEY_GAP_H, metrics.gapHorizontalDp)
                .putFloat(KEY_GAP_V, metrics.gapVerticalDp)
                .apply()
        }
    }
}
