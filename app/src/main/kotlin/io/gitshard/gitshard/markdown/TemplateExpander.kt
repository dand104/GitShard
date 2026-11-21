package io.gitshard.gitshard.markdown

import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class TemplateExpander(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val locale: Locale = Locale.getDefault(),
) {
    fun expand(
        template: String,
        title: String,
        defaultDateFormat: String = "YYYY-MM-DD",
        defaultTimeFormat: String = "HH:mm",
        now: ZonedDateTime = ZonedDateTime.now(clock),
    ): String = PLACEHOLDER_REGEX.replace(template) { match ->
        val key = match.groupValues[1].trim()
        val formatArg = match.groupValues[2].takeIf { it.isNotEmpty() }

        when (key.lowercase()) {
            "title" -> title
            "date" -> formatDateTime(now, formatArg ?: defaultDateFormat) ?: match.value
            "time" -> formatDateTime(now, formatArg ?: defaultTimeFormat) ?: match.value
            else -> match.value
        }
    }

    private fun formatDateTime(dateTime: ZonedDateTime, momentPattern: String): String? =
        runCatching {
            val javaPattern = momentToJavaPattern(momentPattern)
            DateTimeFormatter.ofPattern(javaPattern, locale).format(dateTime)
        }.getOrNull()

    internal fun momentToJavaPattern(pattern: String): String {
        val out = StringBuilder(pattern.length + 8)
        var i = 0
        while (i < pattern.length) {
            if (pattern[i] == '[') {
                val close = pattern.indexOf(']', i + 1)
                if (close > i) {
                    val literal = pattern.substring(i + 1, close).replace("'", "''")
                    out.append('\'').append(literal).append('\'')
                    i = close + 1
                    continue
                }
            }

            val token = MOMENT_TOKENS.firstOrNull { (m, _) -> pattern.startsWith(m, i) }
            if (token != null) {
                out.append(token.second)
                i += token.first.length
            } else {
                val ch = pattern[i]
                if (ch.isLetter()) {
                    out.append('\'').append(ch).append('\'')
                } else if (ch == '\'') {
                    out.append("''")
                } else {
                    out.append(ch)
                }
                i++
            }
        }
        return out.toString()
    }

    private companion object {
        val PLACEHOLDER_REGEX = Regex("""\{\{\s*([a-zA-Z]+)(?::([^}]+))?\s*}}""")

        val MOMENT_TOKENS = listOf(
            "YYYY" to "yyyy",
            "MMMM" to "MMMM",
            "dddd" to "EEEE",
            "MMM" to "MMM",
            "ddd" to "EEE",
            "SSS" to "SSS",
            "YY" to "yy",
            "MM" to "MM",
            "DD" to "dd",
            "dd" to "EE",
            "HH" to "HH",
            "hh" to "hh",
            "mm" to "mm",
            "ss" to "ss",
            "WW" to "ww",
            "ZZ" to "XX",
            "M" to "M",
            "D" to "d",
            "H" to "H",
            "h" to "h",
            "m" to "m",
            "s" to "s",
            "W" to "w",
            "A" to "a",
            "a" to "a",
            "Z" to "XXX",
        )
    }
}
