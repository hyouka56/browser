package com.search.browser.downloads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRecordTest {
    private fun record(
        complete: Boolean = false,
        canceled: Boolean = false,
        interrupted: Boolean = false
    ) = DownloadRecord(
        id = 1,
        name = "download.zip",
        url = "https://example.com/download.zip",
        path = "",
        totalBytes = 0L,
        receivedBytes = 0L,
        speedBytesPerSecond = 0L,
        percent = 0,
        complete = complete,
        paused = false,
        canceled = canceled,
        interrupted = interrupted,
        updatedAt = 0L
    )

    @Test
    fun interruptedDownloadCanBeSelectedForDeletion() {
        assertTrue(record(interrupted = true).isSelectableForDeletion(false))
    }

    @Test
    fun canceledDownloadCanBeSelectedForDeletion() {
        assertTrue(record(canceled = true).isSelectableForDeletion(false))
    }

    @Test
    fun completedDownloadCanBeSelectedForDeletion() {
        assertTrue(record(complete = true).isSelectableForDeletion(false))
    }

    @Test
    fun activeDownloadCannotBeSelectedForDeletion() {
        assertFalse(record().isSelectableForDeletion(false))
    }

    @Test
    fun downloadBeingSavedToPublicDownloadsCannotBeSelected() {
        assertFalse(record(complete = true).isSelectableForDeletion(true))
    }
}
