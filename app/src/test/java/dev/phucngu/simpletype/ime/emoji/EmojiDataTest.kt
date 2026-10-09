package dev.phucngu.simpletype.ime.emoji

import dev.phucngu.simpletype.text.lastGraphemeLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiDataTest {

    @Test fun every_category_has_emoji() {
        for (category in EmojiCategory.entries) {
            assertTrue("$category is empty", EmojiData.emojis(category).isNotEmpty())
        }
    }

    @Test fun no_emoji_is_listed_twice() {
        val all = EmojiCategory.entries.flatMap { EmojiData.emojis(it) }
        val dupes = all.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue("duplicates: $dupes", dupes.isEmpty())
    }

    @Test fun every_entry_is_a_single_emoji() {
        for (category in EmojiCategory.entries) {
            for (emoji in EmojiData.emojis(category)) {
                assertEquals("'$emoji' in $category is not one grapheme", emoji.length, lastGraphemeLength(emoji))
            }
        }
    }
}
