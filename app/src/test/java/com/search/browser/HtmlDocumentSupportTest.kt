package com.search.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlDocumentSupportTest {
    @Test fun acceptsHtmlFileExtensionsCaseInsensitively() {
        assertTrue(HtmlDocumentSupport.isHtmlDocument("index.HTML", null))
        assertTrue(HtmlDocumentSupport.isHtmlDocument("index.htm", "application/octet-stream"))
        assertTrue(HtmlDocumentSupport.isHtmlDocument("page.xhtml", null))
    }

    @Test fun acceptsHtmlMimeTypesWithoutAnExtension() {
        assertTrue(HtmlDocumentSupport.isHtmlDocument("document", "text/html; charset=utf-8"))
        assertTrue(HtmlDocumentSupport.isHtmlDocument("document", "application/xhtml+xml"))
    }

    @Test fun rejectsUnrelatedFiles() {
        assertFalse(HtmlDocumentSupport.isHtmlDocument("report.pdf", "application/pdf"))
        assertFalse(HtmlDocumentSupport.isHtmlDocument("notes.txt", "text/plain"))
    }

    @Test fun removesDirectoriesAndUnsafeCharactersFromCachedName() {
        assertEquals("report.html", HtmlDocumentSupport.safeFileName("../../report.html"))
        assertEquals("my_page.html", HtmlDocumentSupport.safeFileName("my:page.txt"))
    }

    @Test fun suppliesHtmlExtensionForMimeTypedDocuments() {
        assertEquals("page.html", HtmlDocumentSupport.safeFileName("page"))
        assertEquals("report.html", HtmlDocumentSupport.safeFileName("report.txt"))
    }
}
