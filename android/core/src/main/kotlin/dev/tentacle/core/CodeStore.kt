package dev.tentacle.core

import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class CodeEntry(
    val id: String,
    val code: String,
    val sender: String,
    val source: String,
    val snippet: String,
    val receivedMs: Long,
) {
    fun toJson(): Map<String, Any> = linkedMapOf(
        "id" to id,
        "code" to code,
        "sender" to sender,
        "source" to source,
        "snippet" to snippet,
        "received_ms" to receivedMs,
    )
}

enum class CodeCaptureStatus { RELAYED, BLOCKED_PAYMENT, DUPLICATE, NO_CODE, DISABLED }

data class CodeCapture(
    val status: CodeCaptureStatus,
    val sender: String = "",
    val entry: CodeEntry? = null,
)

/** Process-memory-only verification code storage. */
class CodeStore(
    initialEnabled: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val sequence = AtomicLong()
    private var entries = mutableListOf<CodeEntry>()
    private val recentFingerprints = mutableMapOf<String, Long>()

    @Volatile
    var isEnabled: Boolean = initialEnabled
        private set

    fun setEnabled(enabled: Boolean) {
        lock.withLock {
            isEnabled = enabled
            if (!enabled) {
                entries.clear()
                recentFingerprints.clear()
            }
            changed.signalAll()
        }
    }

    fun capture(text: String, fallbackSender: String, source: String): CodeCapture {
        require(source == "notification" || source == "sms") { "Invalid verification code source" }
        if (!isEnabled) return CodeCapture(CodeCaptureStatus.DISABLED)
        val parsed = VerificationCodes.inspect(text, fallbackSender)
            ?: return CodeCapture(CodeCaptureStatus.NO_CODE)
        if (parsed.blockedPayment) {
            return CodeCapture(CodeCaptureStatus.BLOCKED_PAYMENT, parsed.sender)
        }
        return lock.withLock {
            if (!isEnabled) return@withLock CodeCapture(CodeCaptureStatus.DISABLED)
            val now = clock()
            purgeExpired(now)
            val fingerprint = fingerprint(parsed.code, parsed.sender)
            val duplicate = recentFingerprints[fingerprint]?.let { now - it <= DEDUPLICATE_MS } == true
            if (duplicate) return@withLock CodeCapture(CodeCaptureStatus.DUPLICATE, parsed.sender)
            val entry = CodeEntry(
                id = "c-$now-${sequence.incrementAndGet()}",
                code = parsed.code,
                sender = parsed.sender,
                source = source,
                snippet = text.take(MAX_SNIPPET_CHARS),
                receivedMs = now,
            )
            entries += entry
            recentFingerprints[fingerprint] = now
            if (entries.size > MAX_ENTRIES) entries = entries.takeLast(MAX_ENTRIES).toMutableList()
            changed.signalAll()
            CodeCapture(CodeCaptureStatus.RELAYED, parsed.sender, entry)
        }
    }

    fun listAfter(sinceMs: Long): List<CodeEntry> = lock.withLock {
        if (!isEnabled) return@withLock emptyList()
        purgeExpired(clock())
        entries.filter { it.receivedMs > sinceMs }
    }

    fun awaitAfter(sinceMs: Long, timeoutMs: Long): List<CodeEntry> {
        require(timeoutMs >= 0) { "timeoutMs must not be negative" }
        return lock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (isEnabled) {
                purgeExpired(clock())
                val result = entries.filter { it.receivedMs > sinceMs }
                if (result.isNotEmpty() || remaining <= 0) return@withLock result
                remaining = changed.awaitNanos(remaining)
            }
            emptyList()
        }
    }

    fun latest(): CodeEntry? = lock.withLock {
        if (!isEnabled) return@withLock null
        purgeExpired(clock())
        entries.lastOrNull()
    }

    fun codesForRedaction(): List<String> = lock.withLock {
        purgeExpired(clock())
        entries.map { it.code }
    }

    private fun purgeExpired(now: Long) {
        entries.removeAll { now - it.receivedMs >= EXPIRY_MS }
        recentFingerprints.entries.removeAll { now - it.value > DEDUPLICATE_MS }
    }

    private fun fingerprint(code: String, sender: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$sender\u0000$code".toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        const val EXPIRY_MS = 10 * 60 * 1000L
        const val DEDUPLICATE_MS = 2 * 60 * 1000L
        private const val MAX_ENTRIES = 10
        private const val MAX_SNIPPET_CHARS = 160
    }
}
