package io.gitshard.gitshard.data.db

object FtsQueryBuilder {

    fun buildPrefixQuery(raw: String): String? {
        if (raw.isBlank()) return null
        val tokens = ArrayList<String>()
        val current = StringBuilder()

        for (ch in raw) {
            if (ch.isLetterOrDigit()) {
                current.append(ch)
            } else if (current.isNotEmpty()) {
                tokens.add(current.toString())
                current.clear()
            }
        }
        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }
        if (tokens.isEmpty()) return null

        return tokens.joinToString(separator = " ") { "$it*" }
    }
}
