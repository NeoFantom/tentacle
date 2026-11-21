package dev.tentacle.notify

/** Permission facts are read afresh for every delivery; channel choices belong to the owner. */
internal data class NotifyPermissions(
    val urgentAllowed: Boolean,
    val forcedAllowed: Boolean,
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
    fun usesAppAlarm(level: String, quiet: Boolean): Boolean = level in setOf("urgent", "forced") && !quiet

    fun decide(requestedLevel: String, permissions: NotifyPermissions): NotifyDecision {
        val level = when {
            requestedLevel == "forced" && !permissions.forcedAllowed && permissions.urgentAllowed -> "urgent"
            requestedLevel == "forced" && !permissions.forcedAllowed -> "normal"
            requestedLevel == "urgent" && !permissions.urgentAllowed -> "normal"
            else -> requestedLevel
        }
        val downgraded = level != requestedLevel
        val reason = when {
            !permissions.notificationsAllowed -> "notifications_disabled"
            level == "normal" && !permissions.normalChannelAllowed -> "channel_blocked"
            level != "normal" && !permissions.urgentChannelAllowed -> "channel_blocked"
            requestedLevel == "forced" && downgraded -> "forced_not_allowed"
            requestedLevel == "urgent" && downgraded -> "urgent_not_allowed"
            level != "normal" && !permissions.fullScreenAllowed -> "full_screen_not_allowed"
            else -> null
        }
        return NotifyDecision(level, downgraded,
            reason != "notifications_disabled" && reason != "channel_blocked", reason)
    }
}
