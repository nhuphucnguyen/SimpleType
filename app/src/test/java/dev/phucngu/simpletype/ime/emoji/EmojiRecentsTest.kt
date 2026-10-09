package dev.phucngu.simpletype.ime.emoji

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiRecentsTest {

    @Test fun new_emoji_goes_to_the_front() {
        assertEquals(listOf("😀", "🎉"), EmojiRecents.record(listOf("🎉"), "😀"))
    }

    @Test fun reusing_an_emoji_moves_it_to_the_front_without_duplicating() {
        assertEquals(listOf("🎉", "😀", "❤️"), EmojiRecents.record(listOf("😀", "🎉", "❤️"), "🎉"))
    }

    @Test fun list_is_capped_dropping_the_oldest() {
        val full = (1..EmojiRecents.MAX).map { "e$it" }
        val updated = EmojiRecents.record(full, "new")
        assertEquals(EmojiRecents.MAX, updated.size)
        assertEquals("new", updated.first())
        assertEquals("e${EmojiRecents.MAX - 1}", updated.last())
    }

    @Test fun encode_decode_round_trips_multi_codepoint_emoji() {
        val recents = listOf("👨‍👩‍👧", "🇻🇳", "👍🏽", "😀")
        assertEquals(recents, EmojiRecents.decode(EmojiRecents.encode(recents)))
    }

    @Test fun decoding_nothing_gives_an_empty_list() {
        assertEquals(emptyList<String>(), EmojiRecents.decode(null))
        assertEquals(emptyList<String>(), EmojiRecents.decode(""))
    }
}
