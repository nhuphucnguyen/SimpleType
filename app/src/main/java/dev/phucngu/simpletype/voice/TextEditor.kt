package dev.phucngu.simpletype.voice

/**
 * Minimal text-editing surface that [VoiceCommandHandler] operates on.
 *
 * It exposes only the handful of operations command execution needs, mirroring
 * `InputConnection` but free of Android types — so the command/undo logic is unit-testable
 * against a fake editor. The IME provides the real implementation over `InputConnection`.
 */
interface TextEditor {
    /** Up to [maxChars] characters immediately before the cursor (empty if none). */
    fun textBeforeCursor(maxChars: Int): CharSequence

    /** Up to [maxChars] characters immediately after the cursor (empty if none). */
    fun textAfterCursor(maxChars: Int): CharSequence

    /** Insert [text] at the cursor. */
    fun commitText(text: CharSequence)

    /** Delete [charCount] characters immediately before the cursor. */
    fun deleteBeforeCursor(charCount: Int)

    /** Select the entire field contents. */
    fun selectAll()
}
