package dev.phucngu.simpletype.ime

/**
 * The Gboard-style "paste what you just copied" chip on the suggestion strip.
 *
 * A clip is offered for [OFFER_TTL_MS] after it was copied, until it is used: pasted from the
 * chip, or passed over by typing (both call [consume]). A consumed clip stays consumed when the
 * same copy is reported again, as it is each time the keyboard opens; only a new copy brings the
 * chip back. Pure Kotlin so it is JVM-unit-tested; the IME feeds it clipboard events and the time.
 */
class ClipboardSuggestion {

    data class Offer(
        /** The full clip, pasted as is. */
        val text: String,
        /** One short line for the chip, or dots when [masked]. */
        val preview: String,
        /** A password or other sensitive clip: pasted in full, never shown. */
        val masked: Boolean,
    )

    private var text: String? = null
    private var copiedAt = 0L
    private var sensitive = false
    private var consumed = false

    /** The clipboard now holds [clip], copied at [copiedAt] (ClipDescription.getTimestamp). */
    fun onClip(clip: String, copiedAt: Long, sensitive: Boolean) {
        if (clip.isBlank()) {
            text = null
            return
        }
        if (clip == text && copiedAt == this.copiedAt) return // the same copy, seen again
        text = clip
        this.copiedAt = copiedAt
        this.sensitive = sensitive
        consumed = false
    }

    /** The chip was tapped, or the user typed instead: don't offer this copy again. */
    fun consume() {
        consumed = true
    }

    fun offer(now: Long): Offer? {
        val clip = text ?: return null
        if (consumed || now - copiedAt > OFFER_TTL_MS) return null
        return Offer(
            text = clip,
            preview = if (sensitive) MASK else preview(clip),
            masked = sensitive,
        )
    }

    private fun preview(clip: String): String {
        val line = clip.trim().replace(WHITESPACE, " ")
        return if (line.length <= PREVIEW_MAX) line else line.take(PREVIEW_MAX) + "…"
    }

    companion object {
        /** How long after copying the chip stays up, unused. */
        const val OFFER_TTL_MS = 3 * 60 * 1000L
        const val PREVIEW_MAX = 40
        private const val MASK = "•••••"
        private val WHITESPACE = Regex("\\s+")
    }
}
