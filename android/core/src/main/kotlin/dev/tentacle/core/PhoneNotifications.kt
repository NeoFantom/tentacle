package dev.tentacle.core

data class PhoneNotificationInput(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String = "",
    val text: String = "",
    val bigText: String = "",
    val textLines: List<String> = emptyList(),
    val messages: List<String> = emptyList(),
    val subText: String = "",
    val category: String? = null,
    val postTimeMs: Long,
    val ongoing: Boolean = false,
    val foregroundService: Boolean = false,
    val groupSummary: Boolean = false,
)

data class PhoneNotificationEntry(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val subText: String,
    val category: String?,
    val postTimeMs: Long,
    val ongoing: Boolean,
    val removedMs: Long?,
    val updatedMs: Long,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "key" to key, "package" to packageName, "app" to appLabel,
        "title" to title, "text" to text, "sub_text" to subText, "category" to category.orEmpty(),
        "posted_ms" to postTimeMs, "ongoing" to ongoing,
        "removed_ms" to removedMs, "updated_ms" to updatedMs,
    )
}

/** Android-independent extraction and capture policy for posted notifications. */
object PhoneNotifications {
    const val OWN_PACKAGE = "dev.tentacle"
    const val HIDDEN_PAYMENT_TEXT = "[hidden: payment message]"

    fun shouldCapture(
        input: PhoneNotificationInput,
        includeOngoing: Boolean = false,
        excludedPackages: Set<String> = emptySet(),
        ownPackage: String = OWN_PACKAGE,
    ): Boolean = input.key.isNotBlank() && input.packageName.isNotBlank() &&
        input.packageName != ownPackage && input.packageName !in excludedPackages &&
        !input.groupSummary && (includeOngoing || (!input.ongoing && !input.foregroundService))

    fun extractText(input: PhoneNotificationInput): String {
        val extracted = input.messages.asReversed().firstNotNullOfOrNull { it.nonBlank() }
            ?: input.bigText.nonBlank()
            ?: input.textLines.mapNotNull { it.nonBlank() }.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: input.text.nonBlank()
            ?: ""
        return if (isPaymentMessage(input)) HIDDEN_PAYMENT_TEXT else extracted
    }

    fun isPaymentMessage(input: PhoneNotificationInput): Boolean = VerificationCodes.isPaymentMessage(
        buildList {
            add(input.title)
            add(input.text)
            add(input.bigText)
            addAll(input.textLines)
            addAll(input.messages)
            add(input.subText)
        }.joinToString("\n"),
    )

    internal fun normalizedEntry(input: PhoneNotificationInput, updatedMs: Long): PhoneNotificationEntry {
        val payment = isPaymentMessage(input)
        return PhoneNotificationEntry(
            key = input.key,
            packageName = input.packageName.trim().take(255),
            appLabel = input.appLabel.trim().ifEmpty { input.packageName.trim() }.take(200),
            title = if (payment) "" else input.title.trim().take(500),
            text = extractText(input).trim().take(1200),
            subText = if (payment) "" else input.subText.trim().take(500),
            category = input.category?.trim()?.take(100)?.takeIf(String::isNotEmpty),
            postTimeMs = input.postTimeMs.coerceAtLeast(0),
            ongoing = input.ongoing || input.foregroundService,
            removedMs = null,
            updatedMs = updatedMs,
        )
    }

    private fun String.nonBlank(): String? = trim().takeIf(String::isNotEmpty)
}
