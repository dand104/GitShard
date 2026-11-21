package io.gitshard.gitshard.markdown

sealed interface TaskMutationResult {
    data class Updated(
        val content: String,
        val newStatus: Char,
        val isCompleted: Boolean,
    ) : TaskMutationResult

    data class NotATaskLine(val lineNumber: Int) : TaskMutationResult
    data class LineOutOfBounds(val lineNumber: Int, val totalLines: Int) : TaskMutationResult
}

object TaskLineMutator {

    /**
     * Toggles the checkbox in row [lineNumber] (1-based).
     * If [targetStatus] == `null`: `' '` -> `'x'`, any other status -> `' '`.
     */
    fun toggleTask(
        content: String,
        lineNumber: Int,
        targetStatus: Char? = null,
    ): TaskMutationResult {
        if (lineNumber < 1) {
            return TaskMutationResult.LineOutOfBounds(lineNumber, countLines(content))
        }

        var currentLine = 1
        var lineStart = 0
        while (currentLine < lineNumber && lineStart < content.length) {
            val nextNl = content.indexOf('\n', lineStart)
            if (nextNl < 0) {
                return TaskMutationResult.LineOutOfBounds(lineNumber, currentLine)
            }
            lineStart = nextNl + 1
            currentLine++
        }

        if (currentLine != lineNumber || (lineStart == content.length && lineNumber > 1 && !content.endsWith('\n'))) {
            return TaskMutationResult.LineOutOfBounds(lineNumber, currentLine)
        }

        val lineEndExclusive = content.indexOf('\n', lineStart).let { if (it < 0) content.length else it }
        val lineText = content.substring(lineStart, lineEndExclusive).trimEnd('\r')

        val match = TASK_PREFIX_REGEX.find(lineText)
            ?: return TaskMutationResult.NotATaskLine(lineNumber)

        val statusGroup = match.groups[1] ?: return TaskMutationResult.NotATaskLine(lineNumber)
        val statusOffset = lineStart + statusGroup.range.first
        val currentStatus = content[statusOffset]
        val newStatus = targetStatus ?: if (currentStatus == ' ') 'x' else ' '

        val updated = StringBuilder(content.length).apply {
            append(content, 0, statusOffset)
            append(newStatus)
            append(content, statusOffset + 1, content.length)
        }.toString()

        return TaskMutationResult.Updated(
            content = updated,
            newStatus = newStatus,
            isCompleted = newStatus == 'x' || newStatus == 'X',
        )
    }

    private fun countLines(content: String): Int {
        var count = 1
        for (ch in content) {
            if (ch == '\n') count++
        }
        return count
    }

    private val TASK_PREFIX_REGEX = Regex("""^\s*(?:[-*+]|\d+[.)])\s+\[(.)](?:\s|$)""")
}
