package dev.tentacle.codes

import dev.tentacle.core.CodeCapture
import dev.tentacle.core.CodeCaptureStatus
import dev.tentacle.core.CodeStore
import dev.tentacle.core.Redactor

/** The capture/logging boundary never passes message text or code values to the logger. */
internal object CodeCaptureLog {
    fun capture(store: CodeStore, text: String, sender: String, source: String, append: (String) -> Unit): CodeCapture {
        val result = store.capture(text, sender, source)
        val outcome = when (result.status) {
            CodeCaptureStatus.RELAYED -> "relayed"
            CodeCaptureStatus.BLOCKED_PAYMENT -> "blocked: payment"
            else -> return result
        }
        append("verification code received from ${safeSender(result.sender)} ($outcome)")
        return result
    }

    fun safeSender(sender: String, code: String = ""): String {
        val withoutCode = if (code.isEmpty()) sender else sender.replace(code, "[REDACTED]")
        // Notification titles can contain the code, including spaced digit groups.
        return Redactor().redact(withoutCode).replace(Regex("[A-Za-z0-9]*[0-9][A-Za-z0-9]*"), "[REDACTED]")
            .replace(Regex("[\\p{Cntrl}]"), " ").take(120)
    }
}
