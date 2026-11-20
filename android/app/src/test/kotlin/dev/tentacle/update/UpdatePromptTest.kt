package dev.tentacle.update

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdatePromptTest {
    @Test fun `first automatic availability prompts once for the session`() {
        assertTrue(shouldShow("1.2.3"))
        assertFalse(shouldShow("1.2.3", alreadyShowingOrShownThisSession = true))
    }

    @Test fun `newer version prompts after a dismissed version`() {
        assertTrue(shouldShow("1.10.0", dismissedVersion = "v1.9.9"))
    }

    @Test fun `dismissed version and older versions do not prompt`() {
        assertFalse(shouldShow("v1.2.3", dismissedVersion = "1.2.3"))
        assertFalse(shouldShow("1.9.9", dismissedVersion = "1.10.0"))
    }

    @Test fun `manual checks busy states and missing releases do not prompt`() {
        assertFalse(shouldShow("1.2.3", manual = true))
        assertFalse(shouldShow("1.2.3", busy = true))
        assertFalse(shouldShow(null))
        assertFalse(shouldShow("  "))
    }

    @Test fun `automatic check is due on first use and at the six hour boundary`() {
        val interval = UpdatePrompt.AUTOMATIC_CHECK_INTERVAL_MS
        assertTrue(UpdatePrompt.shouldCheckAutomatically(0L, 1L))
        assertTrue(UpdatePrompt.shouldCheckAutomatically(10L, 10L + interval))
    }

    @Test fun `automatic check waits for the interval and tolerates clock rollback`() {
        val interval = UpdatePrompt.AUTOMATIC_CHECK_INTERVAL_MS
        assertFalse(UpdatePrompt.shouldCheckAutomatically(10L, 10L + interval - 1L))
        assertFalse(UpdatePrompt.shouldCheckAutomatically(100L, 99L))
    }

    private fun shouldShow(
        availableVersion: String?,
        dismissedVersion: String? = null,
        manual: Boolean = false,
        alreadyShowingOrShownThisSession: Boolean = false,
        busy: Boolean = false,
    ) = UpdatePrompt.shouldShow(
        availableVersion,
        dismissedVersion,
        manual,
        alreadyShowingOrShownThisSession,
        busy,
    )
}
