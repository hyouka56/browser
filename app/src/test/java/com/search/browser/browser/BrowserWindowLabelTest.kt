package com.search.browser.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserWindowLabelTest {
    @Test fun defaultsToTabAndShowsHostSeparately() {
        assertEquals("Tab", BrowserWindowLabel.shortName("New tab"))
        assertEquals("example.com", BrowserWindowLabel.subtitle("https://www.example.com/path", false))
    }

    @Test fun usesShortPageTitle() {
        assertEquals("Example Domain", BrowserWindowLabel.shortName("  Example   Domain  "))
    }

    @Test fun truncatesLongPageTitle() {
        assertEquals("A very long page ti…", BrowserWindowLabel.shortName("A very long page title that should be shortened"))
    }

    @Test fun privateSubtitleDoesNotExposeUrl() {
        assertEquals("Private", BrowserWindowLabel.subtitle("https://private.example/path", true))
    }
}
