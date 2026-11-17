package dev.phone2agent.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class FileApiTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `resolve accepts primary relative and rooted absolute paths`() {
        val root = Files.createDirectory(tempDir.resolve("primary"))
        val nested = Files.createDirectories(root.resolve("Download/voice"))
        val api = api(root)

        assertEquals(root.toFile().canonicalFile, api.resolve(""))
        assertEquals(root.toFile().canonicalFile, api.resolve("."))
        assertEquals(root.toFile().canonicalFile, api.resolve("/"))
        assertEquals(nested.toFile().canonicalFile, api.resolve("Download/voice"))
        assertEquals(nested.toFile().canonicalFile, api.resolve(nested.toString()))
    }

    @Test
    fun `configured root may be a symlink and absolute alias paths resolve inside it`() {
        val actual = Files.createDirectory(tempDir.resolve("actual-storage"))
        val nested = Files.createDirectories(actual.resolve("Download/voice"))
        val alias = tempDir.resolve("primary-alias")
        try {
            Files.createSymbolicLink(alias, actual)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "Symbolic links are unavailable")
        }
        val api = api(alias)

        assertEquals(actual.toFile().canonicalFile, api.resolve(alias.toString()))
        assertEquals(nested.toFile().canonicalFile, api.resolve(alias.resolve("Download/voice").toString()))
    }

    @Test
    fun `resolve refuses parent and symlink escapes`() {
        val root = Files.createDirectory(tempDir.resolve("primary"))
        val outside = Files.writeString(tempDir.resolve("outside.txt"), "private")
        val link = root.resolve("escape")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "Symbolic links are unavailable")
        }
        val api = api(root)

        assertForbidden { api.resolve("../outside.txt") }
        assertForbidden { api.resolve(link.toString()) }
        assertForbidden { api.resolve(outside.toString()) }
    }

    @Test
    fun `root path prefix does not authorize a sibling directory`() {
        val root = Files.createDirectory(tempDir.resolve("primary"))
        val prefixSibling = Files.createDirectory(tempDir.resolve("primary-backup"))
        val siblingFile = Files.writeString(prefixSibling.resolve("voice.aac"), "outside")
        val api = api(root)

        assertForbidden { api.resolve(prefixSibling.toString()) }
        assertForbidden { api.resolve(siblingFile.toString()) }
    }

    @Test
    fun `resolve applies denied prefixes relative to every root`() {
        val primary = Files.createDirectory(tempDir.resolve("primary"))
        val removable = Files.createDirectory(tempDir.resolve("removable"))
        Files.createDirectories(primary.resolve("Android/data"))
        Files.createDirectories(removable.resolve("Android/obb"))
        Files.createDirectories(primary.resolve("safe/Android/data"))
        val api = FileApi(
            listOf(
                StorageRoot("primary", primary.toFile(), "Internal"),
                StorageRoot("removable", removable.toFile(), "Card"),
            ),
        )

        assertForbidden { api.resolve("Android/data") }
        assertForbidden { api.resolve(removable.resolve("Android/obb").toString()) }
        assertEquals(
            primary.resolve("safe/Android/data").toFile().canonicalFile,
            api.resolve("safe/Android/data"),
        )
    }

    @Test
    fun `ls hides dot entries and sorts directories before files by name`() {
        val root = Files.createDirectory(tempDir.resolve("primary"))
        Files.createDirectory(root.resolve("z-dir"))
        Files.createDirectory(root.resolve("a-dir"))
        Files.writeString(root.resolve("z.txt"), "z")
        Files.writeString(root.resolve("a.txt"), "aaa")
        Files.writeString(root.resolve(".hidden"), "hidden")
        Files.createDirectory(root.resolve(".hidden-dir"))
        val api = api(root)

        val listing = api.ls()
        assertEquals(root.toFile().canonicalPath, listing.path)
        assertEquals(listOf("a-dir", "z-dir", "a.txt", "z.txt"), listing.entries.map { it.name })
        assertEquals(listOf("dir", "dir", "file", "file"), listing.entries.map { it.type })
        assertEquals(0, listing.entries.first().size)
        assertTrue(listing.entries.all { Path.of(it.path).isAbsolute })

        assertEquals(
            listOf(".hidden-dir", "a-dir", "z-dir", ".hidden", "a.txt", "z.txt"),
            api.ls(hidden = true).entries.map { it.name },
        )
    }

    @Test
    fun `ls and stat return expected metadata and missing entries are 404`() {
        val root = Files.createDirectory(tempDir.resolve("primary"))
        val dir = Files.createDirectory(root.resolve("folder"))
        val file = Files.write(root.resolve("voice.aac"), byteArrayOf(1, 2, 3, 4))
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_123))
        val api = api(root)

        val fileEntry = api.stat("voice.aac")
        assertEquals("voice.aac", fileEntry.name)
        assertEquals(file.toFile().canonicalPath, fileEntry.path)
        assertEquals("file", fileEntry.type)
        assertEquals(4, fileEntry.size)
        assertEquals(1_700_000_000_123, fileEntry.mtimeMs)

        val dirEntry = api.stat(dir.toString())
        assertEquals("dir", dirEntry.type)
        assertEquals(0, dirEntry.size)

        val missing = assertThrows(ApiException::class.java) { api.stat("missing") }
        assertEquals(404, missing.status)
        assertEquals("not_found", missing.code)

        val notDirectory = assertThrows(ApiException::class.java) { api.ls("voice.aac") }
        assertEquals(400, notDirectory.status)
        assertEquals("bad_request", notDirectory.code)
    }

    @Test
    fun `find combines extension name time and type filters`() {
        val root = Files.createDirectory(tempDir.resolve("primary"))
        val calls = Files.createDirectory(root.resolve("Calls"))
        val older = file(calls, "TEAM-old.AAC", 1_000)
        val matching = file(calls, "team-new.aac", 2_000)
        file(calls, "team-new.mp3", 2_000)
        file(calls, "other.aac", 2_000)
        val api = api(root)

        val result = api.find(
            FindQuery(
                ext = listOf("aac"),
                name = "TeAm",
                sinceMs = 1_500,
                untilMs = 2_000,
                type = "file",
            ),
        )

        assertEquals(listOf(matching.fileName.toString()), result.entries.map { it.name })
        assertFalse(result.truncated)
        assertFalse(result.entries.any { it.path == older.toString() })

        val dirs = api.find(FindQuery(type = "dir", sort = "name"))
        assertEquals(listOf("Calls"), dirs.entries.map { it.name })
    }

    @Test
    fun `find time bounds are inclusive and name and extension filters ignore case independently`() {
        val root = Files.createDirectory(tempDir.resolve("primary"))
        file(root, "Meeting.AAC", 1_000)
        file(root, "meeting.mp3", 2_000)
        file(root, "other.aac", 3_000)
        val api = api(root)

        assertEquals(
            listOf("Meeting.AAC"),
            api.find(FindQuery(sinceMs = 1_000, untilMs = 1_000)).entries.map { it.name },
        )
        assertEquals(
            listOf("Meeting.AAC"),
            api.find(FindQuery(ext = listOf("AaC"), name = "MEET")).entries.map { it.name },
        )
        assertEquals(
            listOf("Meeting.AAC", "meeting.mp3"),
            api.find(FindQuery(name = "mEeTiNg", sort = "name")).entries.map { it.name },
        )
        assertEquals(
            listOf("other.aac", "Meeting.AAC"),
            api.find(FindQuery(ext = listOf("AAC"), sort = "mtime_desc")).entries.map { it.name },
        )
    }

    @Test
    fun `find respects maximum depth hidden directories denied paths and symlink directories`() {
        val root = Files.createDirectory(tempDir.resolve("primary"))
        file(root, "depth-one.aac", 1_000)
        val levelOne = Files.createDirectory(root.resolve("level-one"))
        file(levelOne, "depth-two.aac", 2_000)
        val hidden = Files.createDirectory(root.resolve(".private"))
        file(hidden, "hidden.aac", 3_000)
        val denied = Files.createDirectories(root.resolve("Android/data"))
        file(denied, "denied.aac", 4_000)
        val linkedTarget = Files.createDirectory(root.resolve("linked-target"))
        file(linkedTarget, "once.aac", 5_000)
        try {
            Files.createSymbolicLink(root.resolve("linked-alias"), linkedTarget)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "Symbolic links are unavailable")
        }
        val api = api(root)

        assertTrue(api.find(FindQuery(maxDepth = 0)).entries.isEmpty())
        assertEquals(
            listOf("depth-one.aac"),
            api.find(FindQuery(maxDepth = 1, sort = "name")).entries.map { it.name },
        )
        val visible = api.find(FindQuery(sort = "name")).entries
        assertEquals(1, visible.count { it.name == "once.aac" })
        assertFalse(visible.any { it.name == "hidden.aac" || it.name == "denied.aac" })

        val withHidden = api.find(FindQuery(hidden = true, sort = "name")).entries
        assertTrue(withHidden.any { it.name == "hidden.aac" })
        assertFalse(withHidden.any { it.name == "denied.aac" })
    }

    @Test
    fun `find supports all sort orders limit and truncation`() {
        val root = Files.createDirectory(tempDir.resolve("primary"))
        file(root, "b.aac", 2_000)
        file(root, "a.aac", 1_000)
        file(root, "c.aac", 3_000)
        val api = api(root)

        assertEquals(
            listOf("c.aac", "b.aac", "a.aac"),
            api.find(FindQuery(sort = "mtime_desc")).entries.map { it.name },
        )
        assertEquals(
            listOf("a.aac", "b.aac", "c.aac"),
            api.find(FindQuery(sort = "mtime_asc")).entries.map { it.name },
        )
        assertEquals(
            listOf("a.aac", "b.aac", "c.aac"),
            api.find(FindQuery(sort = "name")).entries.map { it.name },
        )
        val limited = api.find(FindQuery(limit = 2, sort = "mtime_desc"))
        assertEquals(listOf("c.aac", "b.aac"), limited.entries.map { it.name })
        assertTrue(limited.truncated)

        val exact = api.find(FindQuery(limit = 3))
        assertEquals(3, exact.entries.size)
        assertFalse(exact.truncated)
    }

    @Test
    fun `find default depth is twelve and explicit deeper walks remain iterative`() {
        val root = Files.createDirectory(tempDir.resolve("primary"))
        var directory = root
        repeat(11) { index -> directory = Files.createDirectory(directory.resolve("d$index")) }
        file(directory, "at-depth-twelve.aac", 1_000)
        directory = Files.createDirectory(directory.resolve("d11"))
        file(directory, "at-depth-thirteen.aac", 2_000)

        var deep = Files.createDirectory(root.resolve("deep"))
        repeat(63) { index -> deep = Files.createDirectory(deep.resolve("n$index")) }
        file(deep, "deep-result.aac", 3_000)
        val api = api(root)

        val defaults = api.find(FindQuery(sort = "name")).entries.map { it.name }
        assertTrue("at-depth-twelve.aac" in defaults)
        assertFalse("at-depth-thirteen.aac" in defaults)
        assertFalse("deep-result.aac" in defaults)

        assertTrue(
            api.find(FindQuery(maxDepth = 65, name = "deep-result")).entries.any {
                it.name == "deep-result.aac"
            },
        )
    }

    @Test
    fun `find limit selects globally newest match across subtrees`() {
        val root = Files.createDirectory(tempDir.resolve("primary"))
        val first = Files.createDirectory(root.resolve("first"))
        val second = Files.createDirectory(root.resolve("second"))
        file(first, "old.aac", 1_000)
        file(second, "new.aac", 9_000)
        val api = api(root)

        val result = api.find(FindQuery(limit = 1, sort = "mtime_desc"))
        assertEquals(listOf("new.aac"), result.entries.map { it.name })
        assertTrue(result.truncated)
    }

    @Test
    fun `unreadable directory maps to forbidden`() {
        val root = Files.createDirectory(tempDir.resolve("primary"))
        val locked = Files.createDirectory(root.resolve("locked"))
        val original = try {
            Files.getPosixFilePermissions(locked)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "POSIX permissions are unavailable")
            return
        }
        Files.setPosixFilePermissions(locked, emptySet<PosixFilePermission>())
        try {
            assumeTrue(!Files.isReadable(locked), "Current process bypasses file permissions")
            val error = assertThrows(ApiException::class.java) { api(root).ls("locked") }
            assertEquals(403, error.status)
            assertEquals("forbidden", error.code)
        } finally {
            Files.setPosixFilePermissions(locked, original)
        }
    }

    @Test
    fun `unreadable directory is listed as metadata but find traversal is forbidden`() {
        val root = Files.createDirectory(tempDir.resolve("primary"))
        file(root, "visible.aac", 1_000)
        val locked = Files.createDirectory(root.resolve("locked"))
        file(locked, "hidden-by-permissions.aac", 2_000)
        val original = try {
            Files.getPosixFilePermissions(locked)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "POSIX permissions are unavailable")
            return
        }
        Files.setPosixFilePermissions(locked, emptySet<PosixFilePermission>())
        try {
            assumeTrue(!Files.isReadable(locked), "Current process bypasses file permissions")
            val api = api(root)
            val error = assertThrows(ApiException::class.java) { api.find(FindQuery(root = "locked")) }
            assertEquals(403, error.status)
            assertEquals("forbidden", error.code)

            val traversalError = assertThrows(ApiException::class.java) { api.find(FindQuery(sort = "name")) }
            assertEquals(403, traversalError.status)
            assertEquals("forbidden", traversalError.code)

            val listing = api.ls()
            assertTrue(listing.entries.any { it.name == "locked" && it.type == "dir" })
            assertTrue(listing.entries.any { it.name == "visible.aac" && it.type == "file" })
        } finally {
            Files.setPosixFilePermissions(locked, original)
        }
    }

    @Test
    fun `find rejects out of range limits and malformed extensions`() {
        val root = Files.createDirectory(tempDir.resolve("primary"))
        val api = api(root)
        val invalidQueries = listOf(
            FindQuery(limit = 0),
            FindQuery(limit = 5_001),
            FindQuery(ext = listOf("")),
            FindQuery(ext = listOf(".aac")),
            FindQuery(ext = listOf("aac/mp3")),
            FindQuery(ext = listOf("aac\\mp3")),
            FindQuery(ext = listOf("aac,mp3")),
        )

        invalidQueries.forEach { query ->
            val error = assertThrows(ApiException::class.java) { api.find(query) }
            assertEquals(400, error.status, query.toString())
            assertEquals("bad_request", error.code, query.toString())
        }
    }

    private fun api(root: Path) = FileApi(listOf(StorageRoot("primary", root.toFile(), "Internal")))

    private fun file(parent: Path, name: String, modified: Long): Path {
        val path = Files.writeString(parent.resolve(name), name)
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(modified))
        return path
    }

    private fun assertForbidden(block: () -> Unit) {
        val error = assertThrows(ApiException::class.java, block)
        assertEquals(403, error.status)
        assertEquals("forbidden", error.code)
    }
}
