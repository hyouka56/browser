package com.search.browser

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object BrowserAddressResolver {
    fun resolve(rawInput: String): String {
        val input = rawInput.trim()
        if (input.isEmpty()) return ""

        val lower = input.lowercase()
        if (lower.startsWith("https://") || lower.startsWith("http://")) return input

        val hostPart = input.substringBefore('/').substringBefore('?').substringBefore('#')
        val looksLikeHost = !input.any(Char::isWhitespace) && (
            hostPart.startsWith("www.", ignoreCase = true) ||
            hostPart == "localhost" ||
            hostPart.contains('.') ||
            hostPart.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?$"""))
        )
        if (looksLikeHost) return "https://$input"

        val encoded = URLEncoder.encode(input, StandardCharsets.UTF_8.name())
        return "https://www.google.com/search?q=$encoded"
    }
}
