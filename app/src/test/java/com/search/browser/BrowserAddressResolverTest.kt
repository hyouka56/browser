package com.search.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserAddressResolverTest {
    @Test fun preservesHttpsAddress() {
        assertEquals("https://example.com/path", BrowserAddressResolver.resolve("https://example.com/path"))
    }

    @Test fun upgradesBareDomainToHttps() {
        assertEquals("https://example.com", BrowserAddressResolver.resolve("example.com"))
    }

    @Test fun searchesMultipleWords() {
        assertEquals(
            "https://www.google.com/search?q=hello+world",
            BrowserAddressResolver.resolve("hello world")
        )
    }

    @Test fun searchesSingleWord() {
        assertEquals(
            "https://www.google.com/search?q=pubg",
            BrowserAddressResolver.resolve("pubg")
        )
    }

    @Test fun emptyInputStaysEmpty() {
        assertEquals("", BrowserAddressResolver.resolve("  "))
    }
}
