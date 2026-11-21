package dev.tentacle.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseInfoTest {
    @Test fun `semantic versions compare numerically`() {
        assertEquals(1, Releases.compareVersions("v1.10.0", "1.9.9"))
        assertEquals(-1, Releases.compareVersions("1.2.3", "v2.0.0"))
        assertEquals(0, Releases.compareVersions("v1.2.3", "1.2.3"))
        assertEquals(0, Releases.compareVersions("not-a-version", "1.2.3"))
    }

    @Test fun `prerelease precedence and build metadata follow semver`() {
        val versions = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        versions.zipWithNext().forEach { (left, right) ->
            assertTrue(Releases.compareVersions(left, right) < 0, "$left < $right")
        }
        assertEquals(0, Releases.compareVersions("v1.2.3+build.7", "1.2.3+other"))
        assertTrue(Releases.compareVersions("1.2147483648.0", "1.2147483647.0") > 0)
    }

    @Test fun `malformed releases and missing APK are rejected`() {
        for (json in listOf("[]", "{}", "{", "{\"tag_name\":\"v1.0.0\",\"assets\":[]}",
            "{\"tag_name\":\"1.0.0\",\"assets\":[]}",
            "{\"tag_name\":\"v01.2.3\",\"assets\":[]}",
            "{\"tag_name\":\"v1.02.3\",\"assets\":[]}",
            "{\"tag_name\":\"v1.2.03\",\"assets\":[]}")) {
            assertThrows(RuntimeException::class.java) { Releases.parse(json) }
        }
    }

    @Test fun `preferred APK and matching checksum are selected from release fixture`() {
        val json = javaClass.getResource("/release.json")!!.readText()
        val release = Releases.parse(json)
        assertEquals("2.3.4", release.version)
        assertEquals("tentacle-2.3.4.apk", release.apk.name)
        assertEquals("tentacle-2.3.4.apk.sha256", release.checksum?.name)
    }

    @Test fun `fallback is first APK and unrelated checksum is ignored`() {
        val assets = listOf(
            ReleaseAsset("notes.txt", "https://example.com/notes", 1),
            ReleaseAsset("fork.apk", "https://example.com/fork.apk", 2),
            ReleaseAsset("other.apk.sha256", "https://example.com/other", 3),
        )
        assertEquals("fork.apk", Releases.selectApk("1.0.0", assets)?.name)
        val parsed = Releases.parse("""{"tag_name":"v1.0.0","assets":[{"name":"fork.apk","browser_download_url":"https://example.com/fork.apk","size":2}]}""")
        assertNull(parsed.checksum)
    }
}
