package dev.tentacle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class InstalledVersionHistoryTest {
    @Test fun `append preserves order and skips the current last version`() {
        val initial = InstalledVersionHistory.from(listOf("0.5.0", "0.6.0"))
        assertSame(initial, initial.append("0.6.0"))
        assertEquals(listOf("0.5.0", "0.6.0", "0.7.0"), initial.append("0.7.0").versions)
    }

    @Test fun `history retains only the latest twenty entries`() {
        val history = (1..25).fold(InstalledVersionHistory.from(emptyList())) { current, value ->
            current.append("1.0.$value")
        }
        assertEquals((6..25).map { "1.0.$it" }, history.versions)
    }

    @Test fun `previous is the entry before the latest matching current version`() {
        val history = InstalledVersionHistory.from(listOf("0.5.0", "0.6.0", "0.5.0"))
        assertEquals("0.6.0", history.previous("0.5.0"))
        assertEquals(null, history.previous("0.4.0"))
        assertEquals(null, InstalledVersionHistory.from(listOf("0.7.0")).previous("0.7.0"))
    }

    @Test fun `blank versions are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            InstalledVersionHistory.from(emptyList()).append(" ")
        }
    }
}
