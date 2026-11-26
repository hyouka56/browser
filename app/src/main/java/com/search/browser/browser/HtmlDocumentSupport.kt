package com.search.browser.browser

import java.util.Locale

object HtmlDocumentSupport {
    private val htmlExtensions = setOf("html", "htm", "xhtml")
    private val htmlMimeTypes = setOf("text/html", "application/xhtml+xml")

    fun isHtmlDocument(displayName: String?, mimeType: String?): Boolean {
        val extension = displayName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase(Locale.ROOT)
        val normalizedMimeType = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        return extension?.let(htmlExtensions::contains) == true ||
            normalizedMimeType?.let(htmlMimeTypes::contains) == true
    }

    fun safeFileName(displayName: String?): String {
        val leafName = displayName.orEmpty()
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()
        val cleanedName = leafName.map { character ->
            if (character.isLetterOrDigit() || character in "._- ") character else '_'
        }.joinToString("").trim().trim('.')
        val name = cleanedName.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "page"
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
        return if (extension in htmlExtensions) name else name.substringBeforeLast('.', name) + ".html"
    }
}
