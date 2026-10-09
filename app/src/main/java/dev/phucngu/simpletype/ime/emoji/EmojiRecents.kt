package dev.phucngu.simpletype.ime.emoji

import android.content.SharedPreferences

/** Most-recently-used emoji, newest first. List logic is pure; [load]/[save] persist it. */
object EmojiRecents {
    const val MAX = 32
    private const val PREF_KEY = "emoji_recents"
    // Unit separator: never part of an emoji, so multi-codepoint sequences survive encoding.
    private const val SEPARATOR = '\u001F'

    fun record(recents: List<String>, emoji: String): List<String> =
        (listOf(emoji) + recents.filter { it != emoji }).take(MAX)

    fun encode(recents: List<String>): String = recents.joinToString(SEPARATOR.toString())

    fun decode(raw: String?): List<String> =
        if (raw.isNullOrEmpty()) emptyList() else raw.split(SEPARATOR).filter { it.isNotEmpty() }

    fun load(prefs: SharedPreferences): List<String> = decode(prefs.getString(PREF_KEY, null))

    fun save(prefs: SharedPreferences, recents: List<String>) {
        prefs.edit().putString(PREF_KEY, encode(recents)).apply()
    }
}
