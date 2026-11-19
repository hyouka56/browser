package com.search.browser

import java.net.URI

internal object BrowserWindowLabel {
    fun shortName(title: String, maxLength: Int = 20): String {
        val cleaned = title.replace(Regex("\\s+"), " ").trim()
        val usableTitle = cleaned.takeIf {
            it.isNotBlank() && !it.equals("New tab", ignoreCase = true) && it != "about:blank"
        }
        val candidate = usableTitle ?: "Tab"
        if (candidate.length <= maxLength) return candidate
        return candidate.take((maxLength - 1).coerceAtLeast(1)).trimEnd() + "…"
    }

    fun subtitle(url: String, incognito: Boolean): String {
        if (incognito) return "Private"
        val host = runCatching { URI(url).host }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
        return host ?: url.take(28).ifBlank { "New tab" }
    }
}
