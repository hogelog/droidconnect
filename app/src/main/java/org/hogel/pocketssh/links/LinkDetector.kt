package org.hogel.pocketssh.links

/**
 * Extracts http(s) URLs from a text blob.
 *
 * Trailing punctuation commonly attached to a URL in prose (`.`, `,`, `)`,
 * `]`, `>`, `;`, `:`, `!`, `?`, `"`, `'`) is stripped because it almost never
 * belongs to the URL itself when the URL appears mid-sentence. A trailing
 * `)` is preserved when there is an unmatched `(` earlier in the URL, so
 * Wikipedia-style URLs like `Foo_(disambiguation)` round-trip.
 */
object LinkDetector {

    private val URL_PATTERN = Regex("""https?://[^\s<>"'`\\\[\]{}|^]+""")

    private const val TRAILING_PUNCT = ".,;:!?\"'>"

    fun extractUrls(text: String): List<String> =
        URL_PATTERN.findAll(text)
            .map { trimTrailingPunctuation(it.value) }
            .filter { it.isNotEmpty() }
            .toList()

    private fun trimTrailingPunctuation(raw: String): String {
        var end = raw.length
        while (end > 0) {
            val ch = raw[end - 1]
            when {
                ch in TRAILING_PUNCT -> end--
                ch == ')' && raw.substring(0, end).count { it == '(' } <
                    raw.substring(0, end).count { it == ')' } -> end--
                else -> return raw.substring(0, end)
            }
        }
        return ""
    }
}
