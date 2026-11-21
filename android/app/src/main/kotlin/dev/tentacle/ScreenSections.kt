package dev.tentacle

/** Stable keys for restoring disclosure state independently of translated titles. */
enum class ScreenSection {
    PAIRING, PAIRING_DETAILS, REQUESTS, REQUEST_HISTORY, CONNECTIONS,
    TAILNET_ADVANCED, SERVER_SETUP, SERVER_KEYS, SETTINGS,
}

class ScreenSections(saved: Collection<String>? = null) {
    private val expanded = ScreenSection.entries.filter { it.name in saved.orEmpty() }.toMutableSet()

    fun isOpen(section: ScreenSection): Boolean = section in expanded

    fun setOpen(section: ScreenSection, open: Boolean) {
        if (open) expanded.add(section) else expanded.remove(section)
    }

    fun snapshot(): ArrayList<String> = expanded.mapTo(arrayListOf()) { it.name }
}
