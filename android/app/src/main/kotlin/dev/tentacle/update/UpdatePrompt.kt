package dev.tentacle.update

object UpdatePrompt {
    const val AUTOMATIC_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1_000L

    fun shouldShow(
        availableVersion: String?,
        dismissedVersion: String?,
        manual: Boolean,
        alreadyShowingOrShownThisSession: Boolean,
        busy: Boolean,
    ): Boolean {
        val available = availableVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (manual || alreadyShowingOrShownThisSession || busy) return false
        val dismissed = dismissedVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        return Releases.compareVersions(available, dismissed) > 0
    }

    fun shouldCheckAutomatically(lastCheckMillis: Long, nowMillis: Long): Boolean {
        if (lastCheckMillis == 0L) return true
        if (lastCheckMillis < 0L || nowMillis < lastCheckMillis) return false
        return nowMillis - lastCheckMillis >= AUTOMATIC_CHECK_INTERVAL_MS
    }
}
