package com.search.browser.downloads

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

internal object ApkDownloadArchive {
    private const val MAX_APK_BYTES = 512L * 1024L * 1024L

    @Throws(IOException::class)
    fun extractSingleApk(archive: File, destinationDirectory: File): File {
        if (!archive.isFile) throw IOException("Downloaded archive is missing")

        return ZipFile(archive).use { zip ->
            val apkEntries = mutableListOf<java.util.zip.ZipEntry>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.name.substringAfterLast('/').endsWith(".apk", true)) {
                    apkEntries += entry
                }
            }
            if (apkEntries.size != 1) {
                throw IOException("The archive must contain exactly one APK")
            }

            val entry = apkEntries.single()
            if (entry.size > MAX_APK_BYTES) throw IOException("APK in archive is too large")
            if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) {
                throw IOException("Unable to create the APK extraction folder")
            }

            val entryName = entry.name.substringAfterLast('/')
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .ifBlank { "download.apk" }
            val outputFile = File(destinationDirectory, entryName)
            var copied = 0L
            try {
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            if (copied > MAX_APK_BYTES) throw IOException("APK in archive is too large")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (copied == 0L) throw IOException("APK in archive is empty")
                outputFile
            } catch (error: Throwable) {
                outputFile.delete()
                if (error is IOException) throw error
                throw IOException("Unable to extract APK from archive", error)
            }
        }
    }
}
