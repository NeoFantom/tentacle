package dev.tentacle.core

import java.math.BigInteger

data class ReleaseNote(
    val version: String,
    val en: List<String>,
    val zh: List<String>,
)

class ReleaseNotes private constructor(val entries: List<ReleaseNote>) {
    fun between(previousExclusive: String?, current: String): List<ReleaseNote> {
        val currentVersion = Versions.parse(current) ?: return emptyList()
        val previousVersion = previousExclusive?.let(Versions::parse)
        val currentEntryOnly = previousExclusive == null || previousVersion == null || previousVersion >= currentVersion

        return entries.filter { entry ->
            val version = Versions.parse(entry.version) ?: return@filter false
            version == currentVersion || (!currentEntryOnly && version > previousVersion!! && version <= currentVersion)
        }.sortedWith { left, right ->
            Versions.compare(right.version, left.version) ?: 0
        }
    }

    companion object {
        fun parse(json: String): ReleaseNotes {
            val root = Json.parse(json) as? Map<*, *> ?: error("Release notes must be an object")
            val rawEntries = root["notes"] as? List<*> ?: error("Release notes must contain a notes array")
            return ReleaseNotes(rawEntries.map { rawEntry ->
                val entry = rawEntry as? Map<*, *> ?: error("Release note must be an object")
                ReleaseNote(
                    version = entry.string("version"),
                    en = entry.strings("en"),
                    zh = entry.strings("zh"),
                )
            })
        }

        private fun Map<*, *>.string(key: String): String =
            this[key] as? String ?: error("Release note must contain a string $key")

        private fun Map<*, *>.strings(key: String): List<String> {
            val values = this[key] as? List<*> ?: error("Release note must contain a $key array")
            return values.map { it as? String ?: error("Release note $key entries must be strings") }
        }
    }
}

/** Semantic-version comparison shared by the JVM core and Android update UI. */
object Versions {
    fun compare(left: String, right: String): Int? {
        val a = parse(left) ?: return null
        val b = parse(right) ?: return null
        return a.compareTo(b)
    }

    internal fun parse(value: String): Version? {
        val match = VERSION.matchEntire(value) ?: return null
        val preRelease = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.').orEmpty()
        if (preRelease.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
        return Version((1..3).map { match.groupValues[it].toBigInteger() }, preRelease)
    }

    internal data class Version(
        val core: List<BigInteger>,
        val preRelease: List<String>,
    ) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            core.indices.forEach { index ->
                core[index].compareTo(other.core[index]).takeIf { it != 0 }?.let { return it }
            }
            if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
                return when {
                    preRelease.isEmpty() && other.preRelease.isEmpty() -> 0
                    preRelease.isEmpty() -> 1
                    else -> -1
                }
            }
            for (index in 0 until minOf(preRelease.size, other.preRelease.size)) {
                compareIdentifier(preRelease[index], other.preRelease[index]).takeIf { it != 0 }?.let { return it }
            }
            return preRelease.size.compareTo(other.preRelease.size)
        }

        private fun compareIdentifier(left: String, right: String): Int {
            val leftNumber = left.toBigIntegerOrNull()
            val rightNumber = right.toBigIntegerOrNull()
            return when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
        }
    }

    private val VERSION = Regex(
        "^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
    )
}
