package dev.tentacle.notify

import dev.tentacle.core.Redactor

internal object NotifyLog {
    fun delivery(level: String, shown: Boolean, downgraded: Boolean, text: String, redactor: Redactor): String {
        // Redact BEFORE truncating so a credential straddling the preview boundary is still removed.
        val preview = redactor.redact(text).replace('\n', ' ').codePoints().limit(60)
            .toArray().let { String(it, 0, it.size) }
        return "notify level=$level shown=$shown downgraded=$downgraded text=$preview"
    }
}
