package dev.tentacle.core

class InstalledVersionHistory private constructor(val versions: List<String>) {
    fun append(version: String): InstalledVersionHistory {
        require(version.isNotBlank()) { "Version must not be blank" }
        if (versions.lastOrNull() == version) return this
        return InstalledVersionHistory((versions + version).takeLast(MAX_ENTRIES))
    }

    fun previous(current: String): String? {
        val currentIndex = versions.lastIndexOf(current)
        return versions.getOrNull(currentIndex - 1)
    }

    companion object {
        const val MAX_ENTRIES = 20

        fun from(versions: List<String>): InstalledVersionHistory =
            InstalledVersionHistory(versions.takeLast(MAX_ENTRIES))
    }
}
