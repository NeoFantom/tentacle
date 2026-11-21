package dev.tentacle.update

import dev.tentacle.BuildConfig
import dev.tentacle.core.ReleaseNotes
import dev.tentacle.core.Versions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ReleaseNotesAssetTest {
    @Test fun `bundled release notes are complete and ordered`() {
        val notes = ReleaseNotes.parse(File("src/main/assets/release-notes.json").readText()).entries

        assertTrue(notes.isNotEmpty(), "release notes are empty")
        assertEquals(notes.size, notes.map { it.version }.toSet().size, "release note versions must be unique")
        val drafts = notes.filter { it.version == "unreleased" }
        assertTrue(drafts.size <= 1, "release notes have more than one unreleased entry")
        if (drafts.isNotEmpty()) assertEquals(drafts.single(), notes.first(), "unreleased notes must be first")
        notes.forEach { entry ->
            assertTrue(entry.en.isNotEmpty(), "${entry.version} has no English bullets")
            assertTrue(entry.zh.isNotEmpty(), "${entry.version} has no Chinese bullets")
            assertEquals(entry.en.size, entry.zh.size, "${entry.version} translations have different bullet counts")
            assertTrue(entry.en.all { it.isNotBlank() }, "${entry.version} has a blank English bullet")
            assertTrue(entry.zh.all { it.isNotBlank() }, "${entry.version} has a blank Chinese bullet")
        }

        val released = notes.filter { it.version != "unreleased" }
        released.forEach { assertNotNull(Versions.compare(it.version, it.version), "invalid version ${it.version}") }
        released.zipWithNext().forEach { (newer, older) ->
            assertTrue(
                Versions.compare(newer.version, older.version)!! > 0,
                "${newer.version} must be newer than ${older.version}",
            )
        }
        assertTrue(
            notes.any { it.version == BuildConfig.VERSION_NAME },
            "release notes have no entry for ${BuildConfig.VERSION_NAME}",
        )
    }
}
