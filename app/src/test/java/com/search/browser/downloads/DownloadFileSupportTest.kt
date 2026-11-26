package com.search.browser.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFileSupportTest {
    @Test
    fun preservesOrdinaryFileExtensions() {
        assertEquals("report.pdf", DownloadFileSupport.normalizedName("report.pdf", "fallback.txt", "application/pdf"))
        assertEquals("notes.txt", DownloadFileSupport.normalizedName("", "notes.txt", "text/plain"))
    }

    @Test
    fun derivesMissingExtensionFromMimeType() {
        assertEquals("installer.apk", DownloadFileSupport.normalizedName("installer", null, "application/vnd.android.package-archive"))
        assertEquals("document.pdf", DownloadFileSupport.normalizedName("document", null, "application/pdf"))
        assertEquals("download.bin", DownloadFileSupport.normalizedName(null, null, "application/octet-stream"))
    }

    @Test
    fun derivesExtensionFromParameterizedMimeType() {
        assertEquals("notes.txt", DownloadFileSupport.normalizedName("notes", null, "text/plain; charset=utf-8"))
        assertEquals("installer.apk", DownloadFileSupport.normalizedName("installer", null, "application/vnd.android.package-archive; charset=binary"))
    }

    @Test
    fun stripsDirectoriesAndUnsafeFilenameCharacters() {
        assertEquals("report_.pdf", DownloadFileSupport.normalizedName("../../report?.pdf", null, null))
    }

    @Test
    fun recognizesApkByExtensionOrMimeType() {
        assertTrue(DownloadFileSupport.isApk("installer.apk", "application/octet-stream"))
        assertTrue(DownloadFileSupport.isApk("download", "application/vnd.android.package-archive"))
        assertTrue(DownloadFileSupport.isApk("download", "application/vnd.android.package-archive; charset=binary"))
        assertFalse(DownloadFileSupport.isApk("notes.txt", "text/plain"))
    }

    @Test
    fun infersMimeTypeWhenServerUsesGenericBinaryType() {
        assertEquals("application/vnd.android.package-archive", DownloadFileSupport.mimeTypeFor("installer.apk", "application/octet-stream"))
        assertEquals("application/pdf", DownloadFileSupport.mimeTypeFor("report.pdf", "application/octet-stream"))
        assertEquals("text/plain", DownloadFileSupport.mimeTypeFor("notes.txt", "application/octet-stream"))
    }
}
