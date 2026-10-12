package dev.phucngu.simpletype.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DictationLogTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun log(maxBytes: Long = 1_000_000) = DictationLog(tmp.root.resolve("log.jsonl"), maxBytes) { 1234L }

    @Test
    fun `a field with dictation is written as one json line with the kept text`() {
        val l = log()
        l.segment("Anh ơi.", SegmentJoin.NONE)
        l.segment("chiều nay rảnh không?", SegmentJoin.COMMA)
        l.finishField("Anh ơi, chiều nay anh rảnh không?")
        assertEquals(
            """{"t":1234,"segments":[{"text":"Anh ơi.","join":"NONE"},{"text":"chiều nay rảnh không?","join":"COMMA"}],"kept":"Anh ơi, chiều nay anh rảnh không?"}""",
            l.file.readText().trim(),
        )
        assertEquals(1, l.entries())
    }

    @Test
    fun `fields without dictation are not logged`() {
        val l = log()
        l.finishField("typed by hand")
        assertFalse(l.file.exists())
        assertEquals(0, l.entries())
    }

    @Test
    fun `an unreadable field still logs the segments`() {
        val l = log()
        l.segment("Xin chào.", SegmentJoin.NONE)
        l.finishField(null)
        assertEquals("""{"t":1234,"segments":[{"text":"Xin chào.","join":"NONE"}],"kept":null}""", l.file.readText().trim())
    }

    @Test
    fun `discard drops the pending field`() {
        val l = log()
        l.segment("Mật khẩu.", SegmentJoin.NONE)
        l.discard()
        l.finishField("x")
        assertFalse(l.file.exists())
    }

    @Test
    fun `quotes, backslashes and newlines are escaped`() {
        val l = log()
        l.segment("a \"b\" \\ c", SegmentJoin.NONE)
        l.finishField("line1\nline2\t")
        assertEquals(
            """{"t":1234,"segments":[{"text":"a \"b\" \\ c","join":"NONE"}],"kept":"line1\nline2\t"}""",
            l.file.readText().trim(),
        )
    }

    @Test
    fun `the oldest entries are dropped past the size cap`() {
        val l = log(maxBytes = 300)
        repeat(10) { i ->
            l.segment("câu số $i.", SegmentJoin.NONE)
            l.finishField("câu số $i.")
        }
        val lines = l.file.readLines()
        assertTrue(l.file.length() <= 300)
        assertTrue(lines.last().contains("câu số 9."))
        assertFalse(lines.first().contains("câu số 0."))
    }

    @Test
    fun `clear removes the file`() {
        val l = log()
        l.segment("a.", SegmentJoin.NONE)
        l.finishField("a.")
        l.clear()
        assertFalse(l.file.exists())
        assertEquals(0, l.entries())
    }
}
