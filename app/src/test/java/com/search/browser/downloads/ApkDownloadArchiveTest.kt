package com.search.browser.downloads

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkDownloadArchiveTest {
    @Test
    fun extractsOneApkAndIgnoresOtherArtifactFiles() {
        val archive = createZip(
            "release-signature.txt" to "certificate details",
            "outputs/phone/release/browser.apk" to "apk-content"
        )
        val destination = Files.createTempDirectory("apk-extract-").toFile()

        val apk = ApkDownloadArchive.extractSingleApk(archive, destination)

        assertEquals("browser.apk", apk.name)
        assertEquals("apk-content", apk.readText())
        assertTrue(archive.delete())
        destination.deleteRecursively()
    }

    @Test(expected = IOException::class)
    fun rejectsArchiveWithoutApk() {
        val archive = createZip("readme.txt" to "not an apk")
        ApkDownloadArchive.extractSingleApk(archive, Files.createTempDirectory("apk-empty-").toFile())
    }

    @Test(expected = IOException::class)
    fun rejectsArchiveWithMultipleApks() {
        val archive = createZip("one.apk" to "one", "two.apk" to "two")
        ApkDownloadArchive.extractSingleApk(archive, Files.createTempDirectory("apk-multiple-").toFile())
    }

    private fun createZip(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("browser-artifact-", ".zip")
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return file
    }
}
