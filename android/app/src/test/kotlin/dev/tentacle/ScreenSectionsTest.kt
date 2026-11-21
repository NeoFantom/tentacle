package dev.tentacle

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ScreenSectionsTest {
    @Test fun `a fresh screen keeps forms history and connection details collapsed`() {
        val sections = ScreenSections()
        ScreenSection.entries.forEach { assertFalse(sections.isOpen(it), it.name) }
    }

    @Test fun `closing requests preserves the independently expanded history`() {
        val sections = ScreenSections()
        sections.setOpen(ScreenSection.REQUESTS, true)
        sections.setOpen(ScreenSection.REQUEST_HISTORY, true)
        sections.setOpen(ScreenSection.REQUESTS, false)

        assertFalse(sections.isOpen(ScreenSection.REQUESTS))
        assertTrue(sections.isOpen(ScreenSection.REQUEST_HISTORY))
        sections.setOpen(ScreenSection.REQUESTS, true)
        assertTrue(sections.isOpen(ScreenSection.REQUEST_HISTORY))
        assertFalse(sections.isOpen(ScreenSection.CONNECTIONS))
    }

    @Test fun `recreation restores open and closed sections without sharing mutable state`() {
        val original = ScreenSections()
        original.setOpen(ScreenSection.REQUESTS, true)
        original.setOpen(ScreenSection.CONNECTIONS, true)
        original.setOpen(ScreenSection.REQUESTS, false)
        val saved = original.snapshot()
        val restored = ScreenSections(saved)
        saved.clear()
        original.setOpen(ScreenSection.CONNECTIONS, false)

        assertTrue(restored.isOpen(ScreenSection.CONNECTIONS))
        assertFalse(restored.isOpen(ScreenSection.REQUESTS))
        assertFalse(restored.isOpen(ScreenSection.PAIRING))
    }

    @Test fun `restoring ignores obsolete section names`() {
        val sections = ScreenSections(listOf("REMOVED_SECTION", "REQUESTS"))
        assertEquals(listOf("REQUESTS"), sections.snapshot())
    }
}
