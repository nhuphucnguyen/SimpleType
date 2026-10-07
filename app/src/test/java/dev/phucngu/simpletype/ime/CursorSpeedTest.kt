package dev.phucngu.simpletype.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class CursorSpeedTest {

    @Test fun `default speed keeps the 10dp step`() {
        assertEquals(10f, CursorSpeed.stepDp(CursorSpeed.DEFAULT), 0.0001f)
    }

    @Test fun `max speed is three times the default`() {
        assertEquals(3f, CursorSpeed.MAX, 0f)
        assertEquals(10f / 3f, CursorSpeed.stepDp(CursorSpeed.MAX), 0.0001f)
    }

    @Test fun `half speed doubles the step`() {
        assertEquals(20f, CursorSpeed.stepDp(0.5f), 0.0001f)
    }

    @Test fun `out of range speeds are clamped`() {
        assertEquals(CursorSpeed.stepDp(CursorSpeed.MAX), CursorSpeed.stepDp(10f), 0.0001f)
        assertEquals(CursorSpeed.stepDp(CursorSpeed.MIN), CursorSpeed.stepDp(0f), 0.0001f)
    }
}
