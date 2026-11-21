package io.gitshard.gitshard.markdown

import java.time.LocalDate

data class ParsedNote(
    val title: String?,
    val snippet: String,
    val searchableBody: String,
    val frontmatter: Frontmatter,
    val headings: List<NoteHeading>,
    val links: List<NoteLink>,
    val tags: Set<String>,
    val tasks: List<NoteTask>,
)

data class Frontmatter(
    val rawYaml: String?,
    val properties: List<NoteProperty>,
    val aliases: List<String>,
    val tags: List<String>,
    val endOffset: Int,
) {
    companion object {
        val EMPTY = Frontmatter(
            rawYaml = null,
            properties = emptyList(),
            aliases = emptyList(),
            tags = emptyList(),
            endOffset = 0,
        )
    }
}

data class NoteProperty(
    val key: String,
    val value: String,
)

data class NoteHeading(
    val level: Int,
    val text: String,
    val lineNumber: Int,
)

data class NoteLink(
    val rawTarget: String,
    val targetNote: String,
    val subpath: String?,
    val alias: String?,
    val isEmbed: Boolean,
    val lineNumber: Int,
)

data class NoteTask(
    val lineNumber: Int,
    val text: String,
    val statusChar: Char,
    val isCompleted: Boolean,
    val dueDate: LocalDate?,
    val tags: Set<String>,
)
