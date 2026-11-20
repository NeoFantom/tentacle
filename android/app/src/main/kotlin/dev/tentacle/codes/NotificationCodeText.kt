package dev.tentacle.codes

/** Reject empty or redacted bodies before a title can be mistaken for a code. */
internal object NotificationCodeText {
    fun combine(title: String, parts: List<String>): String? {
        val body = parts.map { it.trim() }.filter { part ->
            part.isNotEmpty() && !part.equals("Sensitive notification content hidden", ignoreCase = true)
        }.distinct()
        if (body.isEmpty()) return null
        return (listOf(title).filter { it.isNotBlank() } + body).distinct().joinToString("\n")
    }
}
