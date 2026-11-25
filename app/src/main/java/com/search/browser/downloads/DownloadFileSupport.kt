package com.search.browser.downloads

import java.net.URLConnection

internal object DownloadFileSupport {
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private const val OCTET_STREAM = "application/octet-stream"

    fun normalizedName(suggestedName: String?, urlName: String?, mimeType: String?): String {
        val suggested = sanitize(suggestedName)
        val fromUrl = sanitize(urlName)
        val candidate = when {
            suggested == null -> fromUrl
            fromUrl == null -> suggested
            suggested.substringAfterLast('.', "").isNotBlank() -> suggested
            else -> fromUrl
        }
        val baseName = candidate ?: "download"
        if (baseName.substringAfterLast('.', "").isNotBlank()) return baseName
        val extension = extensionForMimeType(mimeType)
        return if (extension == null) baseName else "$baseName.$extension"
    }

    fun isApk(name: String, mimeType: String?): Boolean =
        name.endsWith(".apk", ignoreCase = true) ||
            mimeType.equals(APK_MIME_TYPE, ignoreCase = true)

    fun mimeTypeFor(name: String, declaredMimeType: String?): String {
        if (isApk(name, declaredMimeType)) return APK_MIME_TYPE
        val declared = declaredMimeType?.substringBefore(';')?.trim().orEmpty()
        if (declared.isNotBlank() && !declared.equals(OCTET_STREAM, ignoreCase = true)) {
            return declared
        }
        return extensionMimeType(name) ?: URLConnection.guessContentTypeFromName(name) ?: OCTET_STREAM
    }

    private fun sanitize(value: String?): String? {
        val leaf = value.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val cleaned = leaf.map { character ->
            if (character.code < 32 || character in "\\/:*?\"<>|") '_' else character
        }.joinToString("").trim().trim('.')
        return cleaned.takeIf { it.isNotBlank() && it != "." && it != ".." }
    }

    private fun extensionForMimeType(mimeType: String?): String? = when {
        mimeType.equals(APK_MIME_TYPE, true) -> "apk"
        mimeType.equals("application/pdf", true) -> "pdf"
        mimeType.equals("text/plain", true) -> "txt"
        mimeType.equals("text/html", true) -> "html"
        mimeType.equals("application/zip", true) || mimeType.equals("application/x-zip-compressed", true) -> "zip"
        mimeType.equals("image/png", true) -> "png"
        mimeType.equals("image/jpeg", true) -> "jpg"
        mimeType.equals("application/octet-stream", true) -> "bin"
        else -> null
    }

    private fun extensionMimeType(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "zip" -> "application/zip"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "html", "htm" -> "text/html"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> null
    }
}
