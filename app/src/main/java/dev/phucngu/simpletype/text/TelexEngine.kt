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
 * Tone placement uses a simplified but practical rule covering the common cases
 * (marked vowels win; `qu`/`gi` glides are skipped; otherwise last vowel before a
 * final consonant, else the first vowel of the run). Full orthographic placement is
 * left for later tuning — every action stays undoable so mistakes are cheap.
 *
 * The class is pure Kotlin with no Android dependencies so it is unit-testable.
 */
class TelexEngine {

    private val buffer = StringBuilder()

    /** Current Vietnamese display form of the word being composed. */
    val composing: String
        get() = buffer.toString()

    val isEmpty: Boolean
        get() = buffer.isEmpty()

    fun reset() {
        buffer.setLength(0)
    }

    /**
     * Feed one character. Returns true if the engine consumed/handled it (it always
     * does for word characters). Non-word characters should be committed by the caller
     * via [reset] after reading [composing]; the engine does not append separators.
     */
    fun input(c: Char): Boolean {
        if (!c.isLetter()) return false
        val lower = c.lowercaseChar()
        val upper = c.isUpperCase()

        when (lower) {
            's' -> if (applyTone(TONE_SAC, c)) return true
            'f' -> if (applyTone(TONE_HUYEN, c)) return true
            'r' -> if (applyTone(TONE_HOI, c)) return true
            'x' -> if (applyTone(TONE_NGA, c)) return true
            'j' -> if (applyTone(TONE_NANG, c)) return true
            'z' -> if (removeTone()) return true
            'a', 'e', 'o' -> if (applyCircumflex(lower, c)) return true
            'd' -> if (applyDForDd(c)) return true
            'w' -> { applyHorn(c); return true }
        }
        buffer.append(c)
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

    // ---- Horn / breve via w: aw→ă, ow→ơ, uw→ư, lone w→ư ----

    private fun applyHorn(typed: Char) {
        if (buffer.isNotEmpty()) {
            val idx = buffer.length - 1
            val (base, tone) = decompose(buffer[idx])
            val baseLower = base.lowercaseChar()
            val horn = when (baseLower) {
                'a' -> 'ă'; 'o' -> 'ơ'; 'u' -> 'ư'; else -> null
            }
            if (horn != null) {
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
        // Lone w with nothing to attach to → ư (common Telex shorthand)
        buffer.append(if (typed.isUpperCase()) 'Ư' else 'ư')
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

    /**
     * Choose which vowel in the current word should carry the tone.
     * Returns the buffer index, or null if there is no vowel.
     */
    private fun findToneTarget(): Int? {
        val vowels = ArrayList<Int>()
        for (i in buffer.indices) {
            if (isVowel(buffer[i])) vowels.add(i)
        }
        if (vowels.isEmpty()) return null
        if (vowels.size == 1) return vowels[0]

        // 1) A vowel already bearing a circumflex/horn/breve mark wins.
        for (i in vowels.asReversed()) {
            if (isMarkedVowel(buffer[i])) return i
        }

        // 2) Skip glides: "qu…" and "gi…" — the u/i is part of the onset, not the nucleus.
        var run = vowels
        val first = vowels.first()
        if (first > 0) {
            val onset = buffer[first - 1].lowercaseChar()
            val firstBase = decompose(buffer[first]).first.lowercaseChar()
            if ((onset == 'q' && firstBase == 'u') || (onset == 'g' && firstBase == 'i')) {
                if (vowels.size > 1) run = ArrayList(vowels.drop(1))
            }
        }
        if (run.size == 1) return run[0]

        // 3) Word ends in a consonant → tone the last vowel of the run; otherwise the first.
        val endsWithVowel = isVowel(buffer[buffer.length - 1])
        return if (endsWithVowel) run.first() else run.last()
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

    private fun isMarkedVowel(c: Char): Boolean =
        decompose(c).first.lowercaseChar() in MARKED_BASES

    companion object {
        private const val TONE_NONE = 0
        private const val TONE_SAC = 1   // s
        private const val TONE_HUYEN = 2 // f
        private const val TONE_HOI = 3   // r
        private const val TONE_NGA = 4   // x
        private const val TONE_NANG = 5  // j

        private val MARKED_BASES = setOf('ă', 'â', 'ê', 'ô', 'ơ', 'ư')

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
