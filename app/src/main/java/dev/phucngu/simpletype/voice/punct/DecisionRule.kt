package dev.phucngu.simpletype.voice.punct

/**
 * The calibrated decision rule that turns model probabilities into marks and casing; mirrors
 * `predict.py` (`decide`, `end_mark`, `decide_case`) in the vietnamese-punctuation repo.
 *
 *  1. break?  p(COMMA) + p(PERIOD) + p(QUESTION) > [tBreak]
 *  2. which?  argmax of p(COMMA)·e^[commaBias], p(PERIOD), p(QUESTION)·e^[questionBias]
 *  3. the last word always ends the sentence: `?` if p(QUESTION)·e^[questionBias] > p(PERIOD),
 *     else `.` (a bare last word gets a period when p(PERIOD) + p(QUESTION) > [tEndPeriod]).
 *  4. casing: UPPER if p(UPPER) > [tUpper], else Capitalised if p(Cap) > [tCap].
 *
 * VPN2 model files carry their own rule (calibrated with the model); VPN1 files use [LEGACY].
 */
data class DecisionRule(
    val tBreak: Float,
    val commaBias: Float,
    val questionBias: Float,
    val tEndPeriod: Float,
    val tCap: Float,
    val tUpper: Float,
) {
    companion object {
        /** The rule shipped with the VPN1 model (m07): no questions, no casing. */
        val LEGACY = DecisionRule(
            tBreak = 0.6f, commaBias = 0.5f, questionBias = 0f, tEndPeriod = 0.02f, tCap = 0.5f, tUpper = 0.5f,
        )
    }
}
