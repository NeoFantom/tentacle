package dev.tentacle.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseNotesTest {
    private val notes = ReleaseNotes.parse(
        """{"notes":[
            {"version":"unreleased","en":["Next"],"zh":["下个版本"]},
            {"version":"2.0.0","en":["Two"],"zh":["二"]},
            {"version":"1.10.0","en":["Ten"],"zh":["十"]},
            {"version":"broken","en":["Bad"],"zh":["坏"]},
            {"version":"1.2.0","en":["One"],"zh":["一"]}
        ]}""",
    )

    @Test fun `parser preserves localized entries`() {
        assertEquals(5, notes.entries.size)
        assertEquals(ReleaseNote("2.0.0", listOf("Two"), listOf("二")), notes.entries[1])
    }

    @Test fun `between returns every installed release after a skipped version newest first`() {
        assertEquals(listOf("2.0.0", "1.10.0"), notes.between("1.2.0", "2.0.0").map { it.version })
    }

    @Test fun `unknown previous downgrade and missing current select only current when present`() {
        assertEquals(listOf("1.10.0"), notes.between(null, "1.10.0").map { it.version })
        assertEquals(listOf("1.10.0"), notes.between("unknown", "1.10.0").map { it.version })
        assertEquals(listOf("1.2.0"), notes.between("2.0.0", "1.2.0").map { it.version })
        assertTrue(notes.between("1.2.0", "1.3.0").isEmpty())
    }

    @Test fun `unreleased and malformed versions are never selected`() {
        assertEquals(listOf("2.0.0", "1.10.0", "1.2.0"), notes.between("0.1.0", "9.0.0").map { it.version })
    }

    @Test fun `semantic versions compare numerically`() {
        assertTrue(Versions.compare("1.10.0", "1.9.9")!! > 0)
        assertEquals(0, Versions.compare("v1.2.3+one", "1.2.3+two"))
        assertTrue(Versions.compare("1.0.0-rc.1", "1.0.0")!! < 0)
        assertEquals(null, Versions.compare("unreleased", "1.0.0"))
    }

    @Test fun `malformed release notes are rejected`() {
        listOf("[]", "{}", "{\"notes\":[1]}", "{\"notes\":[{\"version\":1,\"en\":[],\"zh\":[]}]}").forEach {
            assertThrows(RuntimeException::class.java) { ReleaseNotes.parse(it) }
        }
    }
}
