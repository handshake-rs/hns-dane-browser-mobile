package com.denuoweb.hnsdane.ui

/**
 * Bounded metadata-only tabs for the single-WebView browser shell.
 *
 * Inactive tabs intentionally retain no WebView, JavaScript context, proxy
 * lease, or background network activity. An ArrayList is preferable to a map
 * here: ordering is the primary operation and the collection is capped at a
 * small constant.
 */
internal class BrowserTabs private constructor(
    private val entries: ArrayList<BrowserTab>,
    private var activeIndex: Int,
) {
    val count: Int
        get() = entries.size

    val active: BrowserTab
        get() = entries[activeIndex]

    fun all(): List<BrowserTab> = entries.toList()

    fun updateActive(url: String, title: String? = null) {
        val normalizedUrl = normalizeUrl(url) ?: return
        val current = active
        entries[activeIndex] = current.copy(
            url = normalizedUrl,
            title = normalizeTitle(title) ?: current.title,
        )
    }

    fun navigateActive(url: String) {
        val normalizedUrl = normalizeUrl(url) ?: return
        val current = active
        entries[activeIndex] = current.copy(
            url = normalizedUrl,
            title = if (normalizedUrl == current.url) current.title else "",
        )
    }

    fun open(homepage: String): BrowserTab? {
        if (entries.size >= MAX_TABS) return null
        val tab = BrowserTab(
            id = nextId(entries),
            url = normalizeUrl(homepage) ?: return null,
            title = "",
        )
        entries += tab
        activeIndex = entries.lastIndex
        return tab
    }

    fun select(id: Long): BrowserTab? {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return null
        activeIndex = index
        return entries[index]
    }

    /** Closes a tab and returns the active tab after the operation. */
    fun close(id: Long, homepage: String): BrowserTab? {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return null
        if (entries.size == 1) {
            val reset = entries[0].copy(
                url = normalizeUrl(homepage) ?: return null,
                title = "",
            )
            entries[0] = reset
            activeIndex = 0
            return reset
        }

        entries.removeAt(index)
        activeIndex = when {
            index < activeIndex -> activeIndex - 1
            index == activeIndex -> index.coerceAtMost(entries.lastIndex)
            else -> activeIndex
        }
        return active
    }

    fun snapshot(): BrowserTabsSnapshot = BrowserTabsSnapshot(
        urls = ArrayList(entries.map(BrowserTab::url)),
        titles = ArrayList(entries.map(BrowserTab::title)),
        activeIndex = activeIndex,
    )

    companion object {
        const val MAX_TABS = 8
        internal const val MAX_URL_CHARS = 16 * 1024
        private const val MAX_TITLE_CHARS = 512

        fun create(homepage: String): BrowserTabs {
            val url = normalizeUrl(homepage)
                ?: throw IllegalArgumentException("homepage must be a bounded non-empty URL")
            return BrowserTabs(
                entries = arrayListOf(BrowserTab(id = 1L, url = url, title = "")),
                activeIndex = 0,
            )
        }

        fun restore(snapshot: BrowserTabsSnapshot?, homepage: String): BrowserTabs {
            val fallback = create(homepage)
            val value = snapshot ?: return fallback
            val restored = ArrayList<BrowserTab>(minOf(value.urls.size, MAX_TABS))
            val requestedActiveIndex = value.activeIndex.coerceIn(
                0,
                minOf(value.urls.lastIndex, MAX_TABS - 1).coerceAtLeast(0),
            )
            var restoredActiveIndex = 0
            value.urls.take(MAX_TABS).forEachIndexed { index, url ->
                val normalizedUrl = normalizeUrl(url) ?: return@forEachIndexed
                restored += BrowserTab(
                    id = restored.size.toLong() + 1L,
                    url = normalizedUrl,
                    title = normalizeTitle(value.titles.getOrNull(index)).orEmpty(),
                )
                if (index <= requestedActiveIndex) {
                    restoredActiveIndex = restored.lastIndex
                }
            }
            if (restored.isEmpty()) return fallback
            return BrowserTabs(
                entries = restored,
                activeIndex = restoredActiveIndex,
            )
        }

        private fun nextId(entries: List<BrowserTab>): Long =
            (entries.maxOfOrNull(BrowserTab::id) ?: 0L)
                .let { if (it == Long.MAX_VALUE) 1L else it + 1L }

        private fun normalizeUrl(url: String?): String? =
            url?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_URL_CHARS }

        private fun normalizeTitle(title: String?): String? =
            title?.trim()?.take(MAX_TITLE_CHARS)?.takeIf(String::isNotEmpty)
    }
}

internal data class BrowserTab(
    val id: Long,
    val url: String,
    val title: String,
)

internal data class BrowserTabsSnapshot(
    val urls: ArrayList<String>,
    val titles: ArrayList<String>,
    val activeIndex: Int,
)
