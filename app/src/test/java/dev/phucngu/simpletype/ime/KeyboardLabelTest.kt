package dev.phucngu.simpletype.ime

import dev.phucngu.simpletype.ime.keyboard.model.Key
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardLabelTest {

    private val letterA = Key('a'.code, "a")

    @Test
    fun caps_lock_displays_uppercase_letter() {
        assertEquals("A", displayLabel(letterA, shifted = false, capsLock = true))
    }

    @Test
    fun inactive_shift_displays_original_letter() {
        assertEquals("a", displayLabel(letterA, shifted = false, capsLock = false))
    }
}
