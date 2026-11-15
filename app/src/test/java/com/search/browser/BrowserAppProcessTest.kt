package com.search.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAppProcessTest {
    @Test
    fun mainApplicationProcessIsAllowedToLoadEasyList() {
        assertTrue(isMainApplicationProcess("com.search.browser", "com.search.browser"))
    }

    @Test
    fun chromiumSandboxedRendererProcessIsSkipped() {
        assertFalse(
            isMainApplicationProcess(
                "com.search.browser:sandboxed_process0:com.cefrium.ChildServices$Sandboxed0:0",
                "com.search.browser"
            )
        )
    }

    @Test
    fun namedSecondaryApplicationProcessIsSkipped() {
        assertFalse(
            isMainApplicationProcess("com.search.browser:remote", "com.search.browser")
        )
    }

    @Test
    fun unrelatedProcessIsSkipped() {
        assertFalse(isMainApplicationProcess("com.other.app", "com.search.browser"))
    }
}
