package dev.phucngu.simpletype.text

/**
 * Notices a word typed in the wrong keyboard language and says how to fix it, so mixed
 * Vietnamese/English typing rarely needs the language switch.
 *
 * Checked once per word, when it ends (space, punctuation, enter):
 *  - **Vietnamese mode:** if the keys as typed are an English word and the Telex result isn't a
 *    Vietnamese word, the keys are put back and the keyboard switches to English
 *    (`more` → mỏe → "more", `test` → tét → "test", `err` → er → "err").
 *  - **English mode:** if replaying the keys through Telex gives a Vietnamese word and the keys
 *    aren't English, the word is converted and the keyboard switches to Vietnamese
 *    (`truowfng` → "trường", `khoong` → "không").
 *
 * When both readings are words (ties such as `last`/lát, `cos`/có), the current language wins
 * unless the other reading is far more common, measured by the dictionaries' Zipf scores:
 * leaving Vietnamese needs the Vietnamese word to be uncommon, and leaving English needs the
 * Vietnamese word to be [EN_TIE_MARGIN] more common and the previous word not to be clearly English.
 *
 * Words with two or more capitals are acronyms or identifiers and are never touched.
 *
 * Pure Kotlin; the dictionaries map a lowercase word to its Zipf frequency × 100.
 */
class LanguageAutoSwitch(
    private val english: Map<String, Int>,
    private val vietnamese: Map<String, Int>,
) {
    /**
     * Vietnamese mode: the English word to commit instead of the Telex result and switch to
     * English for, or null to keep Vietnamese. The arguments are [TelexEngine.typedKeys],
     * [TelexEngine.rawKeys] and [TelexEngine.telexForm].
     */
    fun englishFor(typedKeys: String, rawKeys: String, telexForm: String): String? {
        if (typedKeys.length < 2 || typedKeys == telexForm || isAcronym(typedKeys)) return null
        if (!isLetters(typedKeys)) return null
        // Backspace keeps the keys only roughly in step; trust them only if they replay to the
        // word on screen, so a stale key can never be committed.
        if (replay(typedKeys) != telexForm) return null
        // Every key ("office"), or without a key pressed twice to escape Telex ("yesster").
        val word = typedKeys.takeIf { it.lowercase() in english }
            ?: rawKeys.takeIf { isLetters(it) && it.lowercase() in english }
            ?: return null
        val en = english.getValue(word.lowercase())
        val vi = vietnameseScore(telexForm) ?: return word
        return word.takeIf { vi < VI_COMMON && en >= vi + TIE_MARGIN }
    }

    /**
     * English mode: the Vietnamese word to commit instead of [word] and switch to Vietnamese for,
     * or null to keep it. [previousWord] is the word before it, if any.
     */
    fun vietnameseFor(word: String, previousWord: String? = null): String? {
        if (word.length < 2 || isAcronym(word)) return null
        if (!isLetters(word)) return null
        val form = replay(word)
        if (form.all { it.code < 128 }) return null
        val vi = vietnameseScore(form) ?: return null
        val en = english[word.lowercase()] ?: return form
        if (vi < en + EN_TIE_MARGIN) return null
        return form.takeUnless { previousWord != null && isClearlyEnglish(previousWord) }
    }

    /** A word with no Vietnamese reading at least as likely: "the", "video", but not "cos" (có). */
    private fun isClearlyEnglish(word: String): Boolean {
        val lower = word.lowercase()
        val en = english[lower] ?: return false
        val form = replay(lower)
        val vi = if (form.all { it.code < 128 }) null else vietnamese[form]
        return vi == null || en >= vi + TIE_MARGIN
    }

    private fun vietnameseScore(form: String): Int? {
        // Telex never types a plain-ASCII form differently from its keys except by an escape
        // ("ass" → "as"); the dictionary's ASCII entries are loanwords, not Telex results.
        if (form.all { it.code < 128 }) return null
        return vietnamese[form.lowercase()]
    }

    private fun replay(keys: String): String {
        val engine = TelexEngine()
        keys.forEach { engine.input(it) }
        return engine.telexForm
    }

    private fun isLetters(word: String) = word.all { it in 'a'..'z' || it in 'A'..'Z' }

    private fun isAcronym(word: String): Boolean = word.count { it.isUpperCase() } >= 2

    companion object {
        /** Shared-preferences key for the setting; on by default. */
        const val PREF_ENABLED = "auto_switch_language"

        /** Zipf × 100 above which a Vietnamese word is never given up (~30 per million words). */
        const val VI_COMMON = 450

        /** Zipf × 100 by which English must win a tie to leave Vietnamese (10× more common). */
        const val TIE_MARGIN = 100

        /**
         * Zipf × 100 by which Vietnamese must win a tie to leave English (100× more common):
         * stricter, so a real English word typed alone ("cheers", "teen") stays English.
         */
        const val EN_TIE_MARGIN = 200
    }
}
