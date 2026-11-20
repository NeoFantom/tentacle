package dev.tentacle.notify

/** Permission facts are read afresh for every delivery; channel choices belong to the owner. */
internal data class NotifyPermissions(
    val urgentAllowed: Boolean,
    val notificationsAllowed: Boolean,
    val normalChannelAllowed: Boolean,
    val urgentChannelAllowed: Boolean,
    val fullScreenAllowed: Boolean,
)

internal data class NotifyDecision(
    val level: String,
    val downgraded: Boolean,
    val shown: Boolean,
    val reason: String? = null,
)

internal object NotifyPolicy {
    fun usesAppAlarm(level: String, quiet: Boolean): Boolean = level == "urgent" && !quiet

    fun decide(requestedLevel: String, permissions: NotifyPermissions): NotifyDecision {
        val downgraded = requestedLevel == "urgent" && !permissions.urgentAllowed
        val level = if (downgraded) "normal" else requestedLevel
        val reason = when {
            !permissions.notificationsAllowed -> "notifications_disabled"
            level == "normal" && !permissions.normalChannelAllowed -> "channel_blocked"
            level == "urgent" && !permissions.urgentChannelAllowed -> "channel_blocked"
            downgraded -> "urgent_not_allowed"
            level == "urgent" && !permissions.fullScreenAllowed -> "full_screen_not_allowed"
            else -> null
        }
        return NotifyDecision(level, downgraded,
            reason != "notifications_disabled" && reason != "channel_blocked", reason)
    }
}
