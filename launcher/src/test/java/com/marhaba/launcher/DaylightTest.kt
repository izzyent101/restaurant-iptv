package com.marhaba.launcher
import org.junit.Test
import org.junit.Assert.assertEquals
import java.time.Instant
class DaylightTest {
    private fun phase(time: String) = Daylight.phase(Instant.parse(time).toEpochMilli())
    @Test fun minneapolisAutumnPhases() {
        assertEquals("night", phase("2026-10-27T05:00:00Z"))
        assertEquals("sunrise", phase("2026-10-27T12:40:00Z"))
        assertEquals("day", phase("2026-10-27T18:00:00Z"))
        assertEquals("sunset", phase("2026-10-27T23:00:00Z"))
    }
    @Test fun seasonsShiftEveningLight() {
        assertEquals("day", phase("2026-06-21T23:00:00Z"))
        assertEquals("night", phase("2026-12-21T23:30:00Z"))
    }
}
