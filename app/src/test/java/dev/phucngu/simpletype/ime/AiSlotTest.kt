package dev.phucngu.simpletype.ime

import dev.phucngu.simpletype.ime.AiFixController.State
import org.junit.Assert.assertEquals
import org.junit.Test

class AiSlotTest {
    @Test
    fun empty_field_offers_write_and_text_offers_fix() {
        assertEquals(AiSlot.WRITE, AiSlot.choose(fieldEmpty = true, fixState = State.IDLE, writerOpen = false))
        assertEquals(AiSlot.FIX, AiSlot.choose(fieldEmpty = false, fixState = State.IDLE, writerOpen = false))
    }

    @Test
    fun fix_in_flight_or_undoable_keeps_the_fix_slot() {
        // Undo must stay reachable even if the field reads empty for a moment.
        assertEquals(AiSlot.FIX, AiSlot.choose(fieldEmpty = true, fixState = State.WORKING, writerOpen = false))
        assertEquals(AiSlot.FIX, AiSlot.choose(fieldEmpty = true, fixState = State.UNDOABLE, writerOpen = false))
    }

    @Test
    fun open_writer_keeps_the_write_slot_so_it_can_close() {
        assertEquals(AiSlot.WRITE, AiSlot.choose(fieldEmpty = false, fixState = State.IDLE, writerOpen = true))
    }
}
