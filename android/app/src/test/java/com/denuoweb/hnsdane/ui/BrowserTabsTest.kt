package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTabsTest {
    @Test
    fun opensSelectsAndClosesOrderedTabs() {
        val tabs = BrowserTabs.create("https://home.example/")
        val second = requireNotNull(tabs.open("https://second.example/"))
        val third = requireNotNull(tabs.open("https://third.example/"))

        assertEquals(3, tabs.count)
        assertEquals(third.id, tabs.active.id)
        assertEquals(second.id, tabs.select(second.id)?.id)
        assertEquals(third.id, tabs.close(second.id, "https://home.example/")?.id)
        assertEquals(2, tabs.count)

        tabs.updateActive(third.url, "Third")
        val restored = BrowserTabs.restore(tabs.snapshot(), "https://home.example/")
        assertEquals(2, restored.count)
        assertEquals("https://third.example/", restored.active.url)
        assertEquals("Third", restored.active.title)
    }

    @Test
    fun closingOnlyTabResetsItToHomepage() {
        val tabs = BrowserTabs.create("https://home.example/")
        tabs.updateActive("https://page.example/", "Page")

        val active = requireNotNull(tabs.close(tabs.active.id, "https://home.example/"))

        assertEquals(1, tabs.count)
        assertEquals("https://home.example/", active.url)
        assertEquals("", active.title)
    }

    @Test
    fun enforcesCountAndFieldBounds() {
        val tabs = BrowserTabs.create("https://home.example/")
        repeat(BrowserTabs.MAX_TABS - 1) { index ->
            requireNotNull(tabs.open("https://example.com/$index"))
        }

        assertNull(tabs.open("https://overflow.example/"))
        tabs.updateActive("https://example.com/", "t".repeat(1_000))
        assertEquals(512, tabs.active.title.length)
        tabs.navigateActive("https://example.com/")
        assertEquals(512, tabs.active.title.length)
        tabs.navigateActive("https://navigated.example/")
        assertEquals("", tabs.active.title)
        tabs.updateActive("x".repeat(BrowserTabs.MAX_URL_CHARS + 1), "ignored")
        assertEquals("https://navigated.example/", tabs.active.url)
    }

    @Test
    fun restoreIsBoundedAndFallsBackFromInvalidState() {
        val urls = ArrayList((0..20).map { "https://example.com/$it" })
        urls[0] = "x".repeat(BrowserTabs.MAX_URL_CHARS + 1)
        val restored = BrowserTabs.restore(
            BrowserTabsSnapshot(urls, arrayListOf("invalid", "Second"), 99),
            "https://home.example/",
        )

        assertEquals(BrowserTabs.MAX_TABS - 1, restored.count)
        assertEquals(restored.all().last(), restored.active)
        assertTrue(restored.all().all { it.url.length <= BrowserTabs.MAX_URL_CHARS })
    }
}
