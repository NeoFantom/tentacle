package dev.tentacle.notify

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ForcedAlarmTest {
    @Test fun `shared alarm restores only after the final session and keeps the longest wake budget`() {
        val sessions = ForcedAlarmSessions()
        sessions.start("long", 30_000)
        sessions.start("short", 5_000)
        assertEquals(30_000, sessions.latestDeadline())
        assertFalse(sessions.stop("short"))
        assertEquals(30_000, sessions.latestDeadline())
        assertTrue(sessions.stop("long"))
        assertNull(sessions.latestDeadline())
    }

    @Test fun `saved volume is written once and restored only when verified`() {
        var persisted: Int? = null
        var writes = 0
        var clears = 0
        fun state() = SavedAlarmVolume(
            read = { persisted },
            persist = { writes++; persisted = it; true },
            clear = { clears++; persisted = null; true },
        )

        assertTrue(state().saveOnce(3))
        assertTrue(state().saveOnce(8))
        assertEquals(3, persisted)
        assertEquals(1, writes)
        assertFalse(state().restore { false })
        assertEquals(3, persisted, "failed restoration must remain crash-recoverable")
        assertEquals(0, clears)
        assertTrue(state().restore { it == 3 })
        assertNull(persisted)
        assertEquals(1, clears)
    }

    @Test fun `failed persistence does not register an alarm session`() {
        val sessions = ForcedAlarmSessions()
        val failed = SavedAlarmVolume(read = { null }, persist = { false }, clear = { true })
        assertFalse(beginForcedSession(failed, 4, sessions, "alert", 10_000))
        assertNull(sessions.latestDeadline())
    }

    @Test fun `new process can repair a persisted volume`() {
        var persisted: Int? = 4
        var applied: Int? = null
        val recovered = SavedAlarmVolume(
            read = { persisted },
            persist = { persisted = it; true },
            clear = { persisted = null; true },
        )
        assertTrue(recovered.restore { applied = it; true })
        assertEquals(4, applied)
        assertNull(persisted)
    }
}
