package dev.phucngu.simpletype.ime.email

import android.content.SharedPreferences
import android.text.InputType

/** An email address the user has typed into an email field, with how often and when. */
data class SavedEmail(val address: String, val uses: Int, val lastUsed: Long)

/**
 * Remembers the user's email addresses and suggests them in email fields. People use only a
 * handful of addresses, so the list is small, ranked by use count then recency. The list logic
 * is pure; [load]/[save] persist it.
 */
object SavedEmails {
    const val PREF_ENABLED = "kb_email_suggestions"
    const val MAX_STORED = 10
    const val MAX_SUGGESTIONS = 3
    private const val PREF_KEY = "saved_emails"

    private val EMAIL = Regex("""[^\s@,;]+@[^\s@,;.]+(\.[^\s@,;.]+)*\.[^\s@,;.]{2,}""")
    private val EMAIL_HINT = Regex("""\be-?mail\b""", RegexOption.IGNORE_CASE)
    private val SEPARATORS = Regex("""[\s,;]+""")

    fun isEmail(text: String): Boolean = EMAIL.matches(text)

    /** Email-typed fields, plus plain text fields whose hint says "email" (common on the web). */
    fun isEmailField(inputType: Int, hint: CharSequence?): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> true
            InputType.TYPE_TEXT_VARIATION_NORMAL -> hint?.let { EMAIL_HINT.containsMatchIn(it) } == true
            else -> false
        }
    }

    /** Every valid address in an email field (fields may hold several, separated by , or ;). */
    fun extract(fieldText: CharSequence): List<String> =
        fieldText.split(SEPARATORS).filter { isEmail(it) }

    /** The address being typed: everything before the cursor since the last separator. */
    fun currentToken(beforeCursor: CharSequence): String {
        val text = beforeCursor.toString()
        val start = text.indexOfLast { it.isWhitespace() || it == ',' || it == ';' } + 1
        return text.substring(start)
    }

    fun record(saved: List<SavedEmail>, address: String, now: Long): List<SavedEmail> {
        val clean = address.trim()
        if (!isEmail(clean)) return saved
        val previous = saved.firstOrNull { it.address.equals(clean, ignoreCase = true) }
        val updated = SavedEmail(clean, (previous?.uses ?: 0) + 1, now)
        return ranked(saved.filter { it !== previous } + updated).take(MAX_STORED)
    }

    fun remove(saved: List<SavedEmail>, address: String): List<SavedEmail> =
        saved.filterNot { it.address.equals(address, ignoreCase = true) }

    fun suggest(saved: List<SavedEmail>, token: String, limit: Int = MAX_SUGGESTIONS): List<String> =
        ranked(saved)
            .map { it.address }
            .filter { it.startsWith(token, ignoreCase = true) && !it.equals(token, ignoreCase = true) }
            .take(limit)

    fun ranked(saved: List<SavedEmail>): List<SavedEmail> =
        saved.sortedWith(compareByDescending<SavedEmail> { it.uses }.thenByDescending { it.lastUsed })

    // Tab/newline-separated: neither can appear in a valid address.
    fun encode(saved: List<SavedEmail>): String =
        saved.joinToString("\n") { "${it.address}\t${it.uses}\t${it.lastUsed}" }

    fun decode(raw: String?): List<SavedEmail> =
        raw.orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 3 || !isEmail(parts[0])) return@mapNotNull null
            val uses = parts[1].toIntOrNull() ?: return@mapNotNull null
            val lastUsed = parts[2].toLongOrNull() ?: return@mapNotNull null
            SavedEmail(parts[0], uses, lastUsed)
        }.toList()

    fun load(prefs: SharedPreferences): List<SavedEmail> = decode(prefs.getString(PREF_KEY, null))

    fun save(prefs: SharedPreferences, saved: List<SavedEmail>) {
        prefs.edit().putString(PREF_KEY, encode(saved)).apply()
    }
}
