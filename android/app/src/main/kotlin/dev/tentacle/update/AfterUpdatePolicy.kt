package dev.tentacle.update

/** Decisions shared by the replacement receiver and the visible update-notes flow. */
object AfterUpdatePolicy {
    const val REOPEN_WINDOW_MS = 30 * 60 * 1_000L

    fun isRecentInAppUpdate(startedMillis: Long, target: String?, installed: String, nowMillis: Long): Boolean =
        startedMillis > 0 && target == installed && nowMillis >= startedMillis &&
            nowMillis - startedMillis <= REOPEN_WINDOW_MS

    fun isUpdate(previous: String?, firstInstallMillis: Long, lastUpdateMillis: Long, afterUpdate: Boolean): Boolean =
        previous != null || firstInstallMillis < lastUpdateMillis || afterUpdate

    fun shouldShowNotes(current: String, shown: String?, previous: String?, firstInstallMillis: Long,
                        lastUpdateMillis: Long, afterUpdate: Boolean): Boolean =
        current != shown && isUpdate(previous, firstInstallMillis, lastUpdateMillis, afterUpdate)
}
