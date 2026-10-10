package dev.phucngu.simpletype.voice

import java.io.File

/**
 * Opt-in, local-only log of Vietnamese dictation, used to measure and improve the punctuation
 * model on real speech (vietnamese-punctuation repo, `src/import_dictation_log.py`).
 *
 * For every input field that received dictation, one JSON line is appended when the field is
 * left: the segments as committed (`text`, `join`) and the field's text at that point (`kept`),
 * i.e. what the user kept after editing. Nothing leaves the device unless the user exports the
 * file from Settings. The file is capped at [maxBytes]; the oldest lines are dropped first.
 */
class DictationLog(
    val file: File,
    private val maxBytes: Long = MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val pending = mutableListOf<Pair<String, SegmentJoin>>()

    /** A dictated segment was committed in the current field. */
    fun segment(text: String, join: SegmentJoin) {
        if (text.isNotBlank()) pending += text to join
    }

    /** The current field is being left; [kept] is its text, or null if unreadable. */
    fun finishField(kept: String?) {
        if (pending.isEmpty()) return
        val segments = pending.joinToString(",") { (text, join) ->
            """{"text":${json(text)},"join":"${join.name}"}"""
        }
        pending.clear()
        append("""{"t":${clock()},"segments":[$segments],"kept":${kept?.let(::json) ?: "null"}}""")
    }

    /** Forget the current field (e.g. it turned out to be sensitive). */
    fun discard() = pending.clear()

    fun entries(): Int = if (file.exists()) file.useLines { lines -> lines.count { it.isNotBlank() } } else 0

    fun clear() {
        pending.clear()
        file.delete()
    }

    private fun append(line: String) {
        file.parentFile?.mkdirs()
        val lines = (if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()) + line
        var keep = lines
        var size = keep.sumOf { it.toByteArray().size + 1L }
        while (size > maxBytes && keep.size > 1) {
            size -= keep.first().toByteArray().size + 1L
            keep = keep.drop(1)
        }
        file.writeText(keep.joinToString("\n", postfix = "\n"))
    }

    companion object {
        const val PREF_ENABLED = "voice_dictation_log"
        const val FILE_NAME = "dictation_log.jsonl"
        const val MAX_BYTES = 2_000_000L

        private fun json(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
    }
}
