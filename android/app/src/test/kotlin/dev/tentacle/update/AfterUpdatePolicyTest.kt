package dev.tentacle.update

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AfterUpdatePolicyTest {
    @Test fun `only a matching recent install can reopen automatically`() {
        val start = 1_000L
        val end = start + AfterUpdatePolicy.REOPEN_WINDOW_MS
        assertTrue(AfterUpdatePolicy.isRecentInAppUpdate(start, "0.8.0", "0.8.0", start))
        assertTrue(AfterUpdatePolicy.isRecentInAppUpdate(start, "0.8.0", "0.8.0", end))
        assertFalse(AfterUpdatePolicy.isRecentInAppUpdate(start, "0.8.0", "0.8.0", end + 1))
        assertFalse(AfterUpdatePolicy.isRecentInAppUpdate(start, "0.8.0", "0.8.0", start - 1))
        assertFalse(AfterUpdatePolicy.isRecentInAppUpdate(start, "0.8.0", "0.9.0", end))
        assertFalse(AfterUpdatePolicy.isRecentInAppUpdate(0, null, "0.8.0", start))
    }

    @Test fun `fresh installs stay quiet and an upgrade without history shows current notes`() {
        assertFalse(show(previous = null, first = 100, last = 100))
        assertTrue(show(previous = null, first = 100, last = 200))
    }

    @Test fun `known history and replacement intents detect updates even with identical install times`() {
        assertTrue(show(previous = "0.6.0", first = 100, last = 100))
        assertTrue(show(previous = null, first = 100, last = 100, after = true))
    }

    @Test fun `each version shows once even when a replacement intent is delivered again`() {
        assertFalse(show(previous = "0.6.0", first = 100, last = 200, after = true, shown = "0.8.0"))
        assertTrue(show(previous = "0.6.0", first = 100, last = 200, shown = "0.7.1"))
    }

    private fun show(previous: String?, first: Long, last: Long, after: Boolean = false, shown: String? = null) =
        AfterUpdatePolicy.shouldShowNotes("0.8.0", shown, previous, first, last, after)
}
