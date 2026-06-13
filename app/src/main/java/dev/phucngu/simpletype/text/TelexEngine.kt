package dev.phucngu.simpletype.text

/**
 * Incremental Telex composer for Vietnamese.
 *
 * The engine holds the current word as its already-transformed Vietnamese display form
 * (a [StringBuilder] of `composing`). Each keystroke mutates that buffer in place:
 *
 *  - Letter modifiers form circumflex / horn / breve vowels and đ:
 *      aa→â, ee→ê, oo→ô, dd→đ, aw→ă, ow→ơ, uw→ư, and a lone `w`→ư.
 *  - Tone keys move a tone onto the syllable's main vowel:
 *      s=sắc, f=huyền, r=hỏi, x=ngã, j=nặng, z=remove tone.
 *  - Pressing the same modifier/tone key again reverts and emits the literal key
 *    (Telex's escape behaviour, e.g. "ass" → "as").
 *
 * Tone placement follows the orthographic rules used by Unikey/OpenKey (ported from
 * xkey's `VowelSequenceValidator.calculateTonePosition`): a circumflex/horn vowel wins,
 * triple vowels take the middle vowel, `qu`/`gi` glides are skipped, and the remaining
 * diphthongs follow a small rule set keyed on the vowel pair and whether a final consonant
 * follows. [modernStyle] selects "oà/uý" (new) over "òa/úy" (old) for the oa/oe/uy cases.
 *
 * The tone is also re-placed after every keystroke ([repositionTone]) so that adding a
 * final consonant or a later vowel migrates an already-typed tone to the correct vowel
 * (e.g. "hoaf" then "n" → "hoàn", not "hòan").
 *
 * The class is pure Kotlin with no Android dependencies so it is unit-testable.
 *
 * @param modernStyle when true, oa/oe with a final consonant and all `uy` syllables place
 *   the tone on the second vowel (hoàn/thuý); when false, the traditional placement is used.
 */
class TelexEngine(private val modernStyle: Boolean = true) {

    private val buffer = StringBuilder()

    /**
     * Buffer index of a `ư` that was produced by a *lone* `w` shorthand (i.e. `w` with no
     * preceding vowel to horn), or -1. Valid only for the immediately following keystroke;
     * lets a second `w` escape `ư` → literal `w` so English words can be typed (ww → w).
     */
    private var loneHornIndex = -1

    /**
     * Buffer index of the next coda consonant to "echo-swallow", or -1. Armed when a vowel key
     * circumflexes an earlier vowel in a closed syllable (e.g. `trong` + `o` → `trông`, the user
     * re-typing the rime). The following keystrokes that repeat the existing coda (`ng`) are then
     * absorbed instead of duplicated, so `trongongs` → `trống` rather than `trôngngs`.
     */
    private var codaEchoIndex = -1

    /** Current Vietnamese display form of the word being composed. */
    val composing: String
        get() = buffer.toString()

    val isEmpty: Boolean
        get() = buffer.isEmpty()

    fun reset() {
        buffer.setLength(0)
        loneHornIndex = -1
        codaEchoIndex = -1
    }

    /** Load an existing word into the engine, e.g. when picking up context after a cursor move. */
    fun load(word: String) {
        reset()
        buffer.append(word)
    }

    /**
     * Feed one character. Returns true if the engine consumed/handled it (it always
     * does for word characters). Non-word characters should be committed by the caller
     * via [reset] after reading [composing]; the engine does not append separators.
     */
    fun input(c: Char): Boolean {
        if (!c.isLetter()) return false
        val lower = c.lowercaseChar()

        // These escapes are only armed for the single keystroke that follows.
        val prevLoneHorn = loneHornIndex
        loneHornIndex = -1
        val prevCodaEcho = codaEchoIndex
        codaEchoIndex = -1

        // Re-typing the coda after a circumflex retype (trong+o→trông, then "ng") swallows the
        // duplicate consonants instead of appending them, so the rime is not doubled.
        if (prevCodaEcho in buffer.indices && lower == buffer[prevCodaEcho].lowercaseChar()) {
            val next = prevCodaEcho + 1
            codaEchoIndex = if (next < buffer.length && isConsonant(buffer[next])) next else -1
            return true
        }

        val consumedAsModifier = when (lower) {
            's' -> applyTone(TONE_SAC, c)
            'f' -> applyTone(TONE_HUYEN, c)
            'r' -> applyTone(TONE_HOI, c)
            'x' -> applyTone(TONE_NGA, c)
            'j' -> applyTone(TONE_NANG, c)
            'z' -> removeTone()
            'a', 'e', 'o' -> applyCircumflex(lower, c) || applyCircumflexRetype(lower)
            'd' -> applyDForDd(c)
            'w' -> { applyHorn(c, prevLoneHorn); true }
            else -> false
        }
        if (!consumedAsModifier) buffer.append(c)

        // Adding a letter can change the syllable's shape (new vowel or a final
        // consonant), so migrate any tone already on the word to the right vowel.
        repositionTone()
        return true
    }

    /** Delete one display character. Returns true if there was something to delete. */
    fun backspace(): Boolean {
        if (buffer.isEmpty()) return false
        buffer.deleteCharAt(buffer.length - 1)
        return true
    }

    // ---- Circumflex: aa→â, ee→ê, oo→ô (and revert on third press) ----

    private fun applyCircumflex(lower: Char, typed: Char): Boolean {
        if (buffer.isEmpty()) return false
        val idx = buffer.length - 1
        val (base, tone) = decompose(buffer[idx])
        val baseLower = base.lowercaseChar()
        val circ = when (lower) {
            'a' -> 'â'; 'e' -> 'ê'; 'o' -> 'ô'; else -> return false
        }
        return when (baseLower) {
            lower -> { // e.g. a + a → â, keeping case and tone
                setCharPreserveCase(idx, circ, base.isUpperCase(), tone)
                true
            }
            circ -> { // â + a → revert to "aa"
                setCharPreserveCase(idx, lower, base.isUpperCase(), tone)
                buffer.append(typed)
                true
            }
            else -> false
        }
    }

    /**
     * Circumflex an earlier vowel when the user re-types it across the coda of a closed syllable,
     * e.g. `trong` + `o` → `trông`. Only fires when the syllable's last vowel is a plain a/e/o that
     * matches [lower] and is followed by one or more consonants (the coda). Arms [codaEchoIndex] so
     * a re-typed coda is then absorbed. Returns true if it consumed the key.
     */
    private fun applyCircumflexRetype(lower: Char): Boolean {
        val circ = when (lower) { 'a' -> 'â'; 'e' -> 'ê'; 'o' -> 'ô'; else -> return false }
        // Find the last vowel; everything after it must be the consonant coda (≥1 char).
        val v = buffer.indices.lastOrNull { isVowel(buffer[it]) } ?: return false
        if (v == buffer.length - 1) return false // vowel is last → adjacent case, handled elsewhere
        for (i in v + 1 until buffer.length) if (!isConsonant(buffer[i])) return false
        val (base, tone) = decompose(buffer[v])
        if (base.lowercaseChar() != lower) return false // must be a plain a/e/o matching the key
        setCharPreserveCase(v, circ, base.isUpperCase(), tone)
        codaEchoIndex = v + 1
        return true
    }

    private fun isConsonant(c: Char): Boolean = c.isLetter() && !isVowel(c)

    // ---- Horn / breve via w: aw→ă, ow→ơ, uw→ư, lone w→ư ----

    private fun applyHorn(typed: Char, prevLoneHorn: Int) {
        if (buffer.isNotEmpty()) {
            val idx = buffer.length - 1
            val (base, tone) = decompose(buffer[idx])
            val baseLower = base.lowercaseChar()

            // Second w right after a lone w → ư: escape to a literal w (so English words
            // with w are typeable). Only the standalone ư is replaced, not a real u+w horn.
            if (idx == prevLoneHorn && baseLower == 'ư') {
                buffer[idx] = if (base.isUpperCase()) 'W' else 'w'
                return
            }

            val horn = when (baseLower) {
                'a' -> 'ă'; 'o' -> 'ơ'; 'u' -> 'ư'; else -> null
            }
            if (horn != null) {
                // "uo" + w → "ươ": a single w horns both vowels (hương, đường), unless
                // the u is the "qu" glide ("quo" + w → "quơ", keeping u bare).
                if (baseLower == 'o' && idx >= 1) {
                    val (prevBase, prevTone) = decompose(buffer[idx - 1])
                    val afterQu = idx >= 2 && buffer[idx - 2].lowercaseChar() == 'q'
                    if (prevBase.lowercaseChar() == 'u' && !afterQu) {
                        setCharPreserveCase(idx - 1, 'ư', prevBase.isUpperCase(), prevTone)
                    }
                }
                setCharPreserveCase(idx, horn, base.isUpperCase(), tone)
                return
            }
            // Revert: ă/ơ/ư + w → restore plain vowel and emit literal w
            val plain = when (baseLower) {
                'ă' -> 'a'; 'ơ' -> 'o'; 'ư' -> 'u'; else -> null
            }
            if (plain != null) {
                setCharPreserveCase(idx, plain, base.isUpperCase(), tone)
                buffer.append(typed)
                return
            }
        }
        // Lone w with nothing to attach to → ư (common Telex shorthand). Arm the escape so
        // a following w turns this ư into a literal w (ww → w).
        buffer.append(if (typed.isUpperCase()) 'Ư' else 'ư')
        loneHornIndex = buffer.length - 1
    }

    // ---- d / dd → đ ----

    private fun applyDForDd(typed: Char): Boolean {
        if (buffer.isEmpty()) return false
        val idx = buffer.length - 1
        val prev = buffer[idx]
        return when (prev.lowercaseChar()) {
            'd' -> { buffer[idx] = if (prev.isUpperCase()) 'Đ' else 'đ'; true }
            'đ' -> { buffer[idx] = if (prev.isUpperCase()) 'D' else 'd'; buffer.append(typed); true }
            else -> false
        }
    }

    // ---- Tones ----

    private fun applyTone(tone: Int, typed: Char): Boolean {
        val target = findToneTarget() ?: return false
        val (base, currentTone) = decompose(buffer[target])
        if (currentTone == tone) {
            // Same tone again → cancel and emit the literal key (Telex escape).
            buffer[target] = compose(base, TONE_NONE)
            buffer.append(typed)
            return true
        }
        buffer[target] = compose(base, tone)
        return true
    }

    private fun removeTone(): Boolean {
        val target = findToneTarget() ?: return false
        val (base, currentTone) = decompose(buffer[target])
        if (currentTone == TONE_NONE) return false
        buffer[target] = compose(base, TONE_NONE)
        return true
    }

    /** A vowel in the current word, decomposed into its base letter and diacritics. */
    private data class Vowel(
        val base: Char,        // one of a e i o u y (toneless, diacritic stripped)
        val circumflex: Boolean, // â ê ô
        val horn: Boolean,       // ơ ư
        val bufIndex: Int,       // position in [buffer]
    )

    /**
     * Choose which vowel in the current word should carry the tone.
     * Returns the buffer index, or null if there is no vowel.
     */
    private fun findToneTarget(): Int? {
        val indices = ArrayList<Int>()
        for (i in buffer.indices) if (isVowel(buffer[i])) indices.add(i)
        if (indices.isEmpty()) return null

        // Skip the onset glide of "qu…" and "gi…" — its u/i is not the nucleus.
        if (indices.size > 1) {
            val first = indices.first()
            if (first > 0) {
                val onset = buffer[first - 1].lowercaseChar()
                val firstBase = decompose(buffer[first]).first.lowercaseChar()
                if ((onset == 'q' && firstBase == 'u') || (onset == 'g' && firstBase == 'i')) {
                    indices.removeAt(0)
                }
            }
        }
        if (indices.size == 1) return indices[0]

        val run = indices.map { classifyVowel(buffer[it], it) }
        val hasFinalConsonant = indices.last() < buffer.length - 1
        return run[tonePosition(run, hasFinalConsonant)].bufIndex
    }

    private fun classifyVowel(displayChar: Char, bufIndex: Int): Vowel {
        val base = decompose(displayChar).first.lowercaseChar()
        return when (base) {
            'â' -> Vowel('a', circumflex = true, horn = false, bufIndex)
            'ê' -> Vowel('e', circumflex = true, horn = false, bufIndex)
            'ô' -> Vowel('o', circumflex = true, horn = false, bufIndex)
            'ơ' -> Vowel('o', circumflex = false, horn = true, bufIndex)
            'ư' -> Vowel('u', circumflex = false, horn = true, bufIndex)
            'ă' -> Vowel('a', circumflex = false, horn = false, bufIndex) // breve: like plain a
            else -> Vowel(base, circumflex = false, horn = false, bufIndex)
        }
    }

    /**
     * Index within [run] that should carry the tone. Ported from xkey's
     * `VowelSequenceValidator.calculateTonePosition` (itself a port of Unikey's rule).
     */
    private fun tonePosition(run: List<Vowel>, hasFinalConsonant: Boolean): Int {
        if (run.size == 1) return 0

        // A circumflex vowel (â ê ô) always takes the tone.
        run.indexOfFirst { it.circumflex }.let { if (it >= 0) return it }

        // A horn vowel (ơ ư) takes the tone, except ươ / ươi / ươu → tone on ơ.
        val horn = run.indexOfFirst { it.horn }
        if (horn >= 0) {
            if (horn == 0 && run[0].base == 'u' && run.size >= 2 &&
                run[1].horn && run[1].base == 'o'
            ) return 1
            return horn
        }

        // Triple vowels (oai, uyu, …) place the tone on the middle vowel.
        if (run.size == 3) return 1

        // Diphthongs (no circumflex/horn).
        val a = run[0].base
        val b = run[1].base
        return when {
            // oi, ai, ui and open "ay" keep the tone on the first vowel.
            (a == 'o' || a == 'a' || a == 'u') && b == 'i' -> 0
            a == 'a' && b == 'y' && !hasFinalConsonant -> 0
            // oo (rare, e.g. "thoóng") → second vowel.
            a == 'o' && b == 'o' -> 1
            // ua/ia/iu/io/ya without a final consonant stay on the first vowel
            // (của, mía); after a "gi" onset the i is already dropped above.
            a == 'u' && b == 'a' && !hasFinalConsonant -> 0
            (a == 'i' || a == 'y') && (b == 'a' || b == 'u' || b == 'o') -> 0
            // Modern style: uy → tone on y (thuý, quý, huỳnh).
            modernStyle && a == 'u' && b == 'y' -> 1
            // oa/oe (and the rest): first vowel when the syllable is open, else the
            // vowel adjacent to the final consonant.
            else -> if (hasFinalConsonant) 1 else 0
        }
    }

    /**
     * If the word already carries a tone, move it to whichever vowel [findToneTarget]
     * now points at. This keeps placement correct as the syllable grows or shrinks.
     */
    private fun repositionTone() {
        var current = -1
        var tone = TONE_NONE
        for (i in buffer.indices) {
            val (_, t) = decompose(buffer[i])
            if (t != TONE_NONE) { current = i; tone = t; break }
        }
        if (current < 0) return
        val target = findToneTarget() ?: return
        if (target == current) return
        buffer[current] = compose(decompose(buffer[current]).first, TONE_NONE)
        buffer[target] = compose(decompose(buffer[target]).first, tone)
    }

    // ---- Character (de)composition helpers ----

    /** Returns the toneless base form (keeping any circumflex/horn/breve) and the tone index. */
    private fun decompose(c: Char): Pair<Char, Int> {
        val entry = REVERSE[c.lowercaseChar()] ?: return Pair(c, TONE_NONE)
        val base = if (c.isUpperCase()) entry.first.uppercaseChar() else entry.first
        return Pair(base, entry.second)
    }

    /** Rebuilds a vowel char from its toneless base and a tone index, preserving case. */
    private fun compose(base: Char, tone: Int): Char {
        val table = TONE_TABLE[base.lowercaseChar()] ?: return base
        val toned = table[tone]
        return if (base.isUpperCase()) toned.uppercaseChar() else toned
    }

    private fun setCharPreserveCase(idx: Int, newBaseLower: Char, upper: Boolean, tone: Int) {
        val base = if (upper) newBaseLower.uppercaseChar() else newBaseLower
        buffer[idx] = compose(base, tone)
    }

    private fun isVowel(c: Char): Boolean = REVERSE.containsKey(c.lowercaseChar())

    companion object {
        private const val TONE_NONE = 0
        private const val TONE_SAC = 1   // s
        private const val TONE_HUYEN = 2 // f
        private const val TONE_HOI = 3   // r
        private const val TONE_NGA = 4   // x
        private const val TONE_NANG = 5  // j

        /** base (toneless) → 6 toned forms, ordered: none, sắc, huyền, hỏi, ngã, nặng. */
        private val TONE_TABLE: Map<Char, String> = mapOf(
            'a' to "aáàảãạ",
            'ă' to "ăắằẳẵặ",
            'â' to "âấầẩẫậ",
            'e' to "eéèẻẽẹ",
            'ê' to "êếềểễệ",
            'i' to "iíìỉĩị",
            'o' to "oóòỏõọ",
            'ô' to "ôốồổỗộ",
            'ơ' to "ơớờởỡợ",
            'u' to "uúùủũụ",
            'ư' to "ưứừửữự",
            'y' to "yýỳỷỹỵ",
        )

        /** toned char → (toneless base, tone index). Built once from [TONE_TABLE]. */
        private val REVERSE: Map<Char, Pair<Char, Int>> = buildMap {
            for ((base, forms) in TONE_TABLE) {
                forms.forEachIndexed { tone, ch -> put(ch, Pair(base, tone)) }
            }
        }
    }
}
