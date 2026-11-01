package dev.phucngu.simpletype.text

/**
 * Runs [LanguageAutoSwitch] over a stretch of words, adding look-back: when a word makes the
 * language clear, the unclear words just before it are fixed too.
 *
 * - **Vietnamese mode.** "this makes" first shows "thí" (a real, fairly common Vietnamese word,
 *   so it stays on its own). When "makes" ends, an English-only word that Telex left as typed,
 *   "thí" goes back to "this" and the keyboard switches to English. A word auto switch fixes to
 *   English does the same for the words before it ("lát week" → "last week").
 * - **English mode.** A word that converts to Vietnamese also converts the words before it whose
 *   Vietnamese reading is at least as common ("check mail cos theer" → "check mail có thể").
 *
 * Look-back reaches up to [MAX_LOOK_BACK] words joined by single spaces. Words that Telex leaves as
 * typed ("makes", "cho") are passed over; any other word that doesn't flip stops it. Punctuation,
 * Enter, a manual switch, a cursor move or Backspace ([forget]) start a new stretch.
 *
 * Pure Kotlin: the IME applies each [Outcome] to the text.
 */
class AutoSwitchSession(private val switcher: LanguageAutoSwitch) {

    /** Replace the word [wordsBack] words before the current one, [from], with [to]. */
    data class Rewrite(val wordsBack: Int, val from: String, val to: String)

    /**
     * The current word ends up as [word]; the keyboard switches to Vietnamese (true), English
     * (false) or stays (null); [earlier] words are rewritten.
     */
    class Outcome(val word: String, val switchToVietnamese: Boolean?, val earlier: List<Rewrite>)

    /** A finished word: [shown] on screen, in Vietnamese if [vietnamese]; [other] is its other reading. */
    private class Past(val shown: String, val vietnamese: Boolean, val other: String?, val sameInBoth: Boolean)

    /** The latest words of the stretch, oldest first. */
    private val recent = ArrayList<Past>()

    fun forget() = recent.clear()

    /**
     * A word typed in Vietnamese mode ends; the arguments come from [TelexEngine]. [spaceAfter] is
     * true when a single space ends it, so the next word can look back at it.
     */
    fun finishVietnamese(
        typedKeys: String,
        rawKeys: String,
        telexForm: String,
        shown: String,
        spaceAfter: Boolean,
    ): Outcome {
        val english = switcher.englishFor(typedKeys, rawKeys, telexForm)
        val outcome = when {
            english != null -> Outcome(english, false, lookBack(toEnglish = true, firstMargin = LOOK_BACK_MARGIN))
            switcher.isPlainEnglish(typedKeys, telexForm) -> {
                // Only a confident fix of the word right before switches here: "thí makes".
                val earlier = lookBack(toEnglish = true, firstMargin = PLAIN_MARGIN, passOver = false)
                if (earlier.isEmpty()) Outcome(shown, null, earlier) else Outcome(shown, false, earlier)
            }
            else -> Outcome(shown, null, emptyList())
        }
        val past = if (english != null) {
            Past(english, vietnamese = false, other = telexForm.takeIf { switcher.vietnameseWordScore(it) != null }, sameInBoth = false)
        } else {
            val other = if (switcher.vietnameseWordScore(shown) != null) switcher.englishReading(typedKeys, rawKeys, telexForm) else null
            Past(shown, vietnamese = outcome.switchToVietnamese != false, other = other, sameInBoth = typedKeys == shown)
        }
        remember(past, outcome, spaceAfter)
        return outcome
    }

    /**
     * A word typed in English mode ends. [previousWord] is the word before it on screen, if any;
     * [spaceAfter] as for [finishVietnamese].
     */
    fun finishEnglish(word: String, previousWord: String?, spaceAfter: Boolean): Outcome {
        val vietnamese = switcher.vietnameseFor(word, previousWord)
        val outcome = if (vietnamese != null) {
            Outcome(vietnamese, true, lookBack(toEnglish = false, firstMargin = 0))
        } else {
            Outcome(word, null, emptyList())
        }
        val past = if (vietnamese != null) {
            Past(vietnamese, vietnamese = true, other = word.takeIf { switcher.englishScore(it) != null }, sameInBoth = false)
        } else {
            Past(word, vietnamese = false, other = switcher.vietnameseReading(word), sameInBoth = switcher.isSameInBoth(word))
        }
        remember(past, outcome, spaceAfter)
        return outcome
    }

    private fun remember(current: Past, outcome: Outcome, spaceAfter: Boolean) {
        // Rewritten words changed language; never flip them back.
        if (outcome.earlier.isNotEmpty()) recent.clear()
        if (!spaceAfter) {
            recent.clear()
            return
        }
        recent += current
        while (recent.size > MAX_LOOK_BACK) recent.removeAt(0)
    }

    /**
     * The words before the current one to rewrite now that the text turns English ([toEnglish]) or
     * Vietnamese. The word right before must win by [firstMargin], the ones further back by the
     * usual margin. With [passOver] off, a word Telex leaves as typed right before stops it.
     */
    private fun lookBack(toEnglish: Boolean, firstMargin: Int, passOver: Boolean = true): List<Rewrite> {
        val rewrites = ArrayList<Rewrite>()
        for (back in 1..recent.size) {
            val past = recent[recent.size - back]
            if (past.vietnamese != toEnglish) break
            if (past.sameInBoth) {
                if (passOver || back > 1) continue else break
            }
            val other = past.other ?: break
            val margin = if (rewrites.isEmpty() && back == 1) firstMargin else usualMargin(toEnglish)
            if (!flips(past.shown, other, toEnglish, margin)) break
            rewrites += Rewrite(back, past.shown, other)
        }
        return rewrites
    }

    private fun usualMargin(toEnglish: Boolean) = if (toEnglish) LOOK_BACK_MARGIN else 0

    private fun flips(shown: String, other: String, toEnglish: Boolean, margin: Int): Boolean {
        val (now, then) = if (toEnglish) {
            switcher.vietnameseWordScore(shown) to switcher.englishScore(other)
        } else {
            switcher.englishScore(shown) to switcher.vietnameseWordScore(other)
        }
        if (now == null || then == null) return false
        return then >= now + margin
    }

    companion object {
        const val MAX_LOOK_BACK = 2

        /** Zipf × 100 by which English must beat a Vietnamese word before an English word (~3×). */
        const val LOOK_BACK_MARGIN = 50

        /** As [LOOK_BACK_MARGIN] when only a word Telex left as typed says the text is English (~30×). */
        const val PLAIN_MARGIN = 150
    }
}
