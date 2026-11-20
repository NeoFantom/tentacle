package dev.tentacle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class LoggingTest {
    @TempDir lateinit var directory: File

    @Test
    fun `redacts credentials and login material without hiding harmless text`() {
        val own = "my-current-token"
        val token43 = "A".repeat(43)
        val cases = listOf(
            "Bearer abc.def" to "Bearer [REDACTED]",
            "Authorization: Basic abc" to "Authorization: [REDACTED]",
            "error Authorization: Basic abc" to "error Authorization: [REDACTED]",
            "url?token=hello&safe=yes" to "url?token=[REDACTED]&safe=yes",
            "auth_key=one authkey=two key=three password=four passwd=five secret=six" to
                "auth_key=[REDACTED] authkey=[REDACTED] key=[REDACTED] password=[REDACTED] passwd=[REDACTED] secret=[REDACTED]",
            token43 to "[REDACTED]",
            "tskey-auth-example-secret" to "[REDACTED]",
            "https://control.example/register/code-value" to "https://control.example/register/[REDACTED]",
            "https://login.tailscale.com/a/code-value" to "https://login.tailscale.com/a/[REDACTED]",
            "https://user:pass@example.com/path" to "https://[REDACTED]@example.com/path",
            "https://user@example.com/path" to "https://[REDACTED]@example.com/path",
            "download https://example.com/file?ordinary=value&other=yes" to
                "download https://example.com/file?[REDACTED]",
            "-----BEGIN PRIVATE KEY-----\nsecret bytes\n-----END PRIVATE KEY-----" to "[REDACTED]",
            "-----BEGIN EC PRIVATE KEY-----\nsecret bytes\n-----END EC PRIVATE KEY-----" to "[REDACTED]",
            "value=$own" to "value=[REDACTED]",
        )
        val redactor = Redactor { listOf(own) }
        cases.forEach { (input, expected) -> assertEquals(expected, redactor.redact(input), input) }

        val harmless = "Authorization is required; token bucket; key lime; https://example.com/register/"
        assertEquals(harmless, redactor.redact(harmless))
        assertEquals("B".repeat(42), redactor.redact("B".repeat(42)))
        assertEquals("B".repeat(44), redactor.redact("B".repeat(44)))
        assertEquals("tskey is a label", redactor.redact("tskey is a label"))
        assertEquals("https://example.com/path", redactor.redact("https://example.com/path"))
        assertEquals("Authorization label", redactor.redact("Authorization label"))
        assertEquals("Bearer", redactor.redact("Bearer"))
        assertEquals("foo=bar", redactor.redact("foo=bar"))
        assertEquals("https://example.com/a/code", redactor.redact("https://example.com/a/code"))
        assertEquals("https://example.com/registration/code", redactor.redact("https://example.com/registration/code"))
        assertEquals("https://example.com/path", redactor.redact("https://example.com/path"))
        val publicKey = "-----BEGIN PUBLIC KEY-----\npublic bytes\n-----END PUBLIC KEY-----"
        assertEquals(publicKey, redactor.redact(publicKey))

        var changingSecret = "first-own-token"
        val changing = Redactor { listOf(changingSecret) }
        assertEquals("[REDACTED]", changing.redact("first-own-token"))
        changingSecret = "second-own-token"
        assertEquals("first-own-token", changing.redact("first-own-token"))
        assertEquals("[REDACTED]", changing.redact("second-own-token"))
    }

    @Test
    fun `rotates in order timestamps lines and clears all files`() {
        var second = 0L
        val log = RotatingLog(directory, maxBytesPerFile = 64, files = 2, now = {
            Instant.parse("2025-01-01T00:00:00Z").plusSeconds(second++)
        })
        log.append("first")
        log.append("second")
        log.append("third")
        log.append("fourth")
        log.append("fifth")

        val snapshot = log.snapshot()
        assertTrue(snapshot.indexOf("third") < snapshot.indexOf("fourth"), snapshot)
        assertTrue(snapshot.indexOf("fourth") < snapshot.indexOf("fifth"), snapshot)
        assertFalse(snapshot.contains("first"), snapshot)
        assertTrue(snapshot.lines().first().startsWith("2025-01-01T00:00:02Z"), snapshot)
        assertEquals(2, directory.listFiles()!!.size)

        log.clear()
        assertEquals("", log.snapshot())
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `bounds a huge utf8 line without splitting a character`() {
        val log = RotatingLog(directory, maxBytesPerFile = 64, now = { Instant.parse("2025-01-01T00:00:00Z") })
        log.append("界".repeat(1_000))

        val file = directory.resolve("app.0.log")
        assertTrue(file.length() <= 64, "length=${file.length()}")
        assertTrue(file.readText().startsWith("2025-01-01T00:00:00Z "))
        assertTrue(file.readText().contains("[TRUNCATED]"))
        assertFalse(file.readText().contains('\uFFFD'))
    }

    @Test
    fun `snapshot is capped to newest one mibibyte across three files`() {
        val log = RotatingLog(
            directory, maxBytesPerFile = 512L * 1024, files = 3,
            now = { Instant.parse("2025-01-01T00:00:00Z") },
        )
        log.append("first-" + "a".repeat(600_000))
        log.append("second-" + "b".repeat(600_000))
        log.append("third-" + "c".repeat(600_000))

        val snapshot = log.snapshot()
        assertTrue(snapshot.toByteArray().size <= 1024 * 1024)
        assertFalse(snapshot.contains("first-"))
        assertTrue(snapshot.indexOf("second-") < snapshot.indexOf("third-"), snapshot.take(200))
    }
}
