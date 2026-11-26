package com.search.browser.downloads

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStoragePolicyTest {
    @Test
    fun privateCompletedDownloadIsNeverExportedToPublicDownloads() {
        assertFalse(record(privateMode = true).shouldSaveToPublicDownloads())
    }

    @Test
    fun publicCompletedStagingDownloadCanBeExported() {
        assertTrue(record(privateMode = false).shouldSaveToPublicDownloads())
    }

    @Test
    fun contentUriIsNotCopiedToPublicDownloadsAgain() {
        assertFalse(record(privateMode = false, path = "content://downloads/item/1").shouldSaveToPublicDownloads())
    }

    @Test
    fun acceptsOnlyDirectFilesOwnedByTheExpectedDownloadAndDirectory() {
        val root = Files.createTempDirectory("browser-download-policy-").toFile()
        val owned = File(root, "17-report.txt")
        val otherId = File(root, "18-report.txt")
        val nested = File(root, "nested/17-report.txt")
        val outside = Files.createTempDirectory("browser-download-outside-").resolve("17-report.txt").toFile()
        try {
            assertTrue(DownloadStoragePolicy.isOwnedStagingFile(17, owned.path, listOf(root)))
            assertFalse(DownloadStoragePolicy.isOwnedStagingFile(17, otherId.path, listOf(root)))
            assertFalse(DownloadStoragePolicy.isOwnedStagingFile(17, nested.path, listOf(root)))
            assertFalse(DownloadStoragePolicy.isOwnedStagingFile(17, outside.path, listOf(root)))
            assertFalse(DownloadStoragePolicy.isOwnedStagingFile(17, "content://downloads/item/17", listOf(root)))
        } finally {
            root.deleteRecursively()
            outside.parentFile?.deleteRecursively()
        }
    }

    private fun record(privateMode: Boolean, path: String = "/app/downloads/17-report.txt") = DownloadRecord(
        id = 17,
        name = "report.txt",
        url = "https://example.com/report.txt",
        path = path,
        totalBytes = 10L,
        receivedBytes = 10L,
        speedBytesPerSecond = 0L,
        percent = 100,
        complete = true,
        paused = false,
        canceled = false,
        interrupted = false,
        updatedAt = 1L,
        privateMode = privateMode
    )
}
