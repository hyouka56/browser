package com.search.browser.downloads

import java.io.File

internal object DownloadStoragePolicy {
    fun isOwnedStagingFile(
        downloadId: Int,
        path: String?,
        stagingDirectories: Collection<File>
    ): Boolean {
        if (downloadId < 0 || path.isNullOrBlank() || path.startsWith("content://")) return false
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        val expectedPrefix = "$downloadId-"
        if (!file.name.startsWith(expectedPrefix) || file.name.length <= expectedPrefix.length) return false
        return stagingDirectories.any { directory ->
            val root = runCatching { directory.canonicalFile }.getOrNull() ?: return@any false
            file.parentFile == root
        }
    }
}
