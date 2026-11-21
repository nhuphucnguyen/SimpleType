package dev.phucngu.simpletype.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardSuggestionTest {
    private val t0 = 1_000_000L
    private val ttl = ClipboardSuggestion.OFFER_TTL_MS

    @Test fun fresh_copy_is_offered() {
        val s = ClipboardSuggestion()
        s.onClip("hello world", copiedAt = t0, sensitive = false)
        assertEquals("hello world", s.offer(now = t0 + 1_000)?.text)
    }

    @Test fun nothing_copied_offers_nothing() {
        assertNull(ClipboardSuggestion().offer(now = t0))
    }

    @Test fun offer_expires() {
        val s = ClipboardSuggestion()
        s.onClip("hello", copiedAt = t0, sensitive = false)
        assertNull(s.offer(now = t0 + ttl + 1))
    }

    // Opening the keyboard re-reads the clipboard: an old clip must not look new.
    @Test fun stale_clip_seen_late_is_not_offered() {
        val s = ClipboardSuggestion()
        s.onClip("old", copiedAt = t0 - ttl - 1, sensitive = false)
        assertNull(s.offer(now = t0))
    }

    @Test fun pasting_consumes_the_offer() {
        val s = ClipboardSuggestion()
        s.onClip("hello", copiedAt = t0, sensitive = false)
        s.consume()
        assertNull(s.offer(now = t0 + 1))
    }

    // The same clip re-reported (keyboard reopened) stays consumed.
    @Test fun same_clip_reported_again_stays_consumed() {
        val s = ClipboardSuggestion()
        s.onClip("hello", copiedAt = t0, sensitive = false)
        s.consume()
        s.onClip("hello", copiedAt = t0, sensitive = false)
        assertNull(s.offer(now = t0 + 1))
    }

    @Test fun new_copy_after_consume_is_offered_again() {
        val s = ClipboardSuggestion()
        s.onClip("hello", copiedAt = t0, sensitive = false)
        s.consume()
        s.onClip("hello", copiedAt = t0 + 5_000, sensitive = false)
        assertEquals("hello", s.offer(now = t0 + 6_000)?.text)
    }

    @Test fun blank_clip_is_ignored() {
        val s = ClipboardSuggestion()
        s.onClip("hello", copiedAt = t0, sensitive = false)
        s.onClip("   \n", copiedAt = t0 + 1, sensitive = false)
        assertNull(s.offer(now = t0 + 2))
    }

    @Test fun preview_is_one_line_and_short() {
        val s = ClipboardSuggestion()
        s.onClip("  first line\n\tsecond   line  ", copiedAt = t0, sensitive = false)
        assertEquals("first line second line", s.offer(now = t0)?.preview)

        s.onClip("x".repeat(100), copiedAt = t0 + 1, sensitive = false)
        val preview = s.offer(now = t0 + 1)!!.preview
        assertEquals(ClipboardSuggestion.PREVIEW_MAX + 1, preview.length)
        assertEquals('…', preview.last())
    }

    // Passwords and other sensitive clips paste in full but never show on screen.
    @Test fun sensitive_clip_is_masked_but_pastes_in_full() {
        val s = ClipboardSuggestion()
        s.onClip("hunter2", copiedAt = t0, sensitive = true)
        val offer = s.offer(now = t0)!!
        assertEquals("hunter2", offer.text)
        assertEquals(true, offer.masked)
        assertEquals("•••••", offer.preview)
    }

    @Test fun remaining_time_counts_down_to_expiry() {
        val s = ClipboardSuggestion()
        assertEquals(0L, s.remainingMs(now = t0))
        s.onClip("hello", copiedAt = t0, sensitive = false)
        assertEquals(ttl - 1_000, s.remainingMs(now = t0 + 1_000))
        s.consume()
        assertEquals(0L, s.remainingMs(now = t0 + 1_000))
    }
}
