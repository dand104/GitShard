package io.gitshard.gitshard.markdown

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import java.time.LocalDate

class BaseParser {
    private val flavour = GFMFlavourDescriptor()

    fun parse(content: String): ParsedNote {
        val lineIndex = LineIndex(content)
        val frontmatter = parseFrontmatter(content)
        val bodyOffset = frontmatter.endOffset
        val body = if (bodyOffset == 0) content else content.substring(bodyOffset)

        val root = MarkdownParser(flavour, true, CancellationToken.NonCancellable)
            .buildMarkdownTreeFromString(body as CharSequence)
        val excludedRanges = ArrayList<IntRange>()
        val rawHeadings = ArrayList<Pair<Int, NoteHeading>>()
        val rawTasks = ArrayList<RawTask>()

        collectFromAst(
            node = root,
            body = body,
            bodyOffset = bodyOffset,
            lineIndex = lineIndex,
            excludedRanges = excludedRanges,
            headings = rawHeadings,
            tasks = rawTasks,
        )

        val astExcluded = mergeRanges(excludedRanges)
        val commentRanges = collectObsidianComments(body, astExcluded)
        val mergedExcluded = if (commentRanges.isEmpty()) astExcluded else mergeRanges(astExcluded + commentRanges)

        val headings = rawHeadings
            .filterNot { (offset, _) -> isExcluded(offset, commentRanges) }
            .map { it.second }

        val tasks = rawTasks
            .filterNot { isExcluded(it.offset, commentRanges) }
            .map { raw ->
                NoteTask(
                    lineNumber = raw.lineNumber,
                    text = raw.text,
                    statusChar = raw.statusChar,
                    isCompleted = raw.isCompleted,
                    dueDate = raw.dueDate,
                    tags = if (raw.rawText.isEmpty()) {
                        emptySet()
                    } else {
                        extractTags(raw.rawText, mergedExcluded, baseOffset = raw.rawTextOffset)
                    },
                )
            }

        val links = extractWikilinks(
            body = body,
            bodyOffset = bodyOffset,
            lineIndex = lineIndex,
            excluded = mergedExcluded,
        )

        val inlineTags = extractTags(body, mergedExcluded)
        val allTags = LinkedHashSet<String>(frontmatter.tags.size + inlineTags.size).apply {
            frontmatter.tags.forEach { normalizeTag(it)?.let(::add) }
            addAll(inlineTags)
        }

        val titleFromYaml = frontmatter.properties
            .firstOrNull { it.key.equals("title", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
        val title = titleFromYaml ?: headings.firstOrNull { it.level == 1 }?.text

        val snippet = buildSnippet(body)

        return ParsedNote(
            title = title,
            snippet = snippet,
            searchableBody = body,
            frontmatter = frontmatter,
            headings = headings,
            links = links,
            tags = allTags,
            tasks = tasks,
        )
    }

    private fun collectFromAst(
        node: ASTNode,
        body: String,
        bodyOffset: Int,
        lineIndex: LineIndex,
        excludedRanges: MutableList<IntRange>,
        headings: MutableList<Pair<Int, NoteHeading>>,
        tasks: MutableList<RawTask>,
    ) {
        when (node.type) {
            MarkdownElementTypes.CODE_FENCE,
            MarkdownElementTypes.CODE_BLOCK,
            MarkdownElementTypes.CODE_SPAN,
            MarkdownElementTypes.HTML_BLOCK,
            MarkdownTokenTypes.HTML_TAG,
            MarkdownElementTypes.LINK_DESTINATION,
            MarkdownElementTypes.AUTOLINK,
            GFMTokenTypes.GFM_AUTOLINK,
                -> {
                excludedRanges.add(node.startOffset until node.endOffset)
                return
            }

            MarkdownElementTypes.ATX_1,
            MarkdownElementTypes.ATX_2,
            MarkdownElementTypes.ATX_3,
            MarkdownElementTypes.ATX_4,
            MarkdownElementTypes.ATX_5,
            MarkdownElementTypes.ATX_6,
            MarkdownElementTypes.SETEXT_1,
            MarkdownElementTypes.SETEXT_2,
                -> {
                extractHeading(node, body, bodyOffset, lineIndex)?.let {
                    headings.add(node.startOffset to it)
                }
            }

            MarkdownElementTypes.LIST_ITEM -> {
                extractTaskFromListItem(node, body, bodyOffset, lineIndex)?.let(tasks::add)
            }
        }

        for (child in node.children) {
            collectFromAst(child, body, bodyOffset, lineIndex, excludedRanges, headings, tasks)
        }
    }

    private fun extractHeading(
        node: ASTNode,
        body: String,
        bodyOffset: Int,
        lineIndex: LineIndex,
    ): NoteHeading? {
        val level = headingLevel(node.type) ?: return null
        val contentNode = node.children.firstOrNull {
            it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT
        }
        val rawText = (contentNode ?: node).getTextInNode(body).toString()
        val cleaned = rawText
            .trim()
            .removePrefix("#".repeat(level))
            .replace(TRAILING_ATX_HASHES, "")
            .trim()
        if (cleaned.isEmpty()) return null

        return NoteHeading(
            level = level,
            text = cleaned,
            lineNumber = lineIndex.lineOf(bodyOffset + node.startOffset),
        )
    }

    private fun extractTaskFromListItem(
        node: ASTNode,
        body: String,
        bodyOffset: Int,
        lineIndex: LineIndex,
    ): RawTask? {
        val start = node.startOffset
        val lineEnd = body.indexOf('\n', start).let { if (it == -1) node.endOffset else minOf(it, node.endOffset) }
        val firstLine = body.substring(start, lineEnd).trimEnd('\r')

        val match = TASK_LINE_REGEX.matchEntire(firstLine) ?: return null
        val statusChar = match.groupValues[1][0]
        val textGroup = match.groups[2]
        val rawTaskText = textGroup?.value.orEmpty()
        val trimmedTaskText = rawTaskText.trim()
        val rawTextOffset = start + (textGroup?.range?.first ?: 0)

        val dueDate = parseDueDate(trimmedTaskText)

        return RawTask(
            offset = start,
            lineNumber = lineIndex.lineOf(bodyOffset + start),
            text = trimmedTaskText,
            rawText = rawTaskText,
            rawTextOffset = rawTextOffset,
            statusChar = statusChar,
            isCompleted = statusChar == 'x' || statusChar == 'X',
            dueDate = dueDate,
        )
    }

    private fun extractWikilinks(
        body: String,
        bodyOffset: Int,
        lineIndex: LineIndex,
        excluded: List<IntRange>,
    ): List<NoteLink> {
        val results = ArrayList<NoteLink>()
        for (match in WIKILINK_REGEX.findAll(body)) {
            if (isExcluded(match.range.first, excluded)) continue

            val isEmbed = match.groupValues[1] == "!"
            val inner = match.groupValues[2]
            val (rawTarget, alias) = splitWikilinkAlias(inner)
            if (rawTarget.isBlank()) continue

            val hashIndex = rawTarget.indexOf('#')
            val targetNote: String
            val subpath: String?
            if (hashIndex >= 0) {
                targetNote = rawTarget.substring(0, hashIndex).trim()
                subpath = rawTarget.substring(hashIndex).trim().takeIf { it.length > 1 }
            } else {
                targetNote = rawTarget.trim()
                subpath = null
            }

            if (targetNote.isEmpty() && subpath == null) continue

            results.add(
                NoteLink(
                    rawTarget = rawTarget.trim(),
                    targetNote = targetNote,
                    subpath = subpath,
                    alias = alias?.takeIf { it.isNotEmpty() },
                    isEmbed = isEmbed,
                    lineNumber = lineIndex.lineOf(bodyOffset + match.range.first),
                ),
            )
        }
        return results
    }

    private fun extractTags(
        text: String,
        excluded: List<IntRange>,
        baseOffset: Int = 0,
    ): Set<String> {
        val tags = LinkedHashSet<String>()
        for (match in INLINE_TAG_REGEX.findAll(text)) {
            val hashPos = match.groups[1]?.range?.first ?: continue
            if (isExcluded(baseOffset + hashPos, excluded)) continue
            val candidate = match.groupValues[1]
            normalizeTag(candidate)?.let(tags::add)
        }
        return tags
    }

    private fun collectObsidianComments(body: String, astExcluded: List<IntRange>): List<IntRange> {
        val comments = ArrayList<IntRange>()
        var searchFrom = 0
        while (searchFrom < body.length) {
            val open = body.indexOf("%%", searchFrom)
            if (open < 0) break
            if (isExcluded(open, astExcluded)) {
                searchFrom = open + 2
                continue
            }
            val close = body.indexOf("%%", open + 2)
            if (close < 0) {
                comments.add(open until body.length)
                break
            }
            comments.add(open until (close + 2))
            searchFrom = close + 2
        }
        return comments
    }

    private fun parseFrontmatter(content: String): Frontmatter {
        val startOffset = if (content.startsWith('\uFEFF')) 1 else 0
        if (!content.startsWith("---", startOffset)) return Frontmatter.EMPTY

        val firstLineEnd = findLineEnd(content, startOffset)
        val opener = content.substring(startOffset, firstLineEnd).trimEnd('\r', ' ', '\t')
        if (opener != "---") return Frontmatter.EMPTY

        val yamlStart = nextLineStart(content, firstLineEnd)
        if (yamlStart >= content.length) return Frontmatter.EMPTY

        var cursor = yamlStart
        var closingLineStart = -1
        var afterClosingOffset = -1

        while (cursor <= content.length) {
            val lineEnd = findLineEnd(content, cursor)
            val trimmed = content.substring(cursor, lineEnd).trimEnd('\r', ' ', '\t')
            if (trimmed == "---" || trimmed == "...") {
                closingLineStart = cursor
                afterClosingOffset = nextLineStart(content, lineEnd)
                break
            }
            val next = nextLineStart(content, lineEnd)
            if (next == cursor) break
            cursor = next
        }

        if (closingLineStart < 0) return Frontmatter.EMPTY

        val rawYaml = content.substring(yamlStart, closingLineStart).trimEnd('\r', '\n')
        val properties = ArrayList<NoteProperty>()
        val aliases = ArrayList<String>()
        val tags = ArrayList<String>()

        var currentListKey: String? = null
        var sawKeyOrEmpty = rawYaml.isBlank()

        for (rawLine in rawYaml.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue

            if (currentListKey != null && (trimmed.startsWith("- ") || trimmed == "-")) {
                val item = unquoteYamlValue(trimmed.removePrefix("-").trim())
                if (item.isNotEmpty()) {
                    recordFrontmatterEntry(currentListKey, item, properties, aliases, tags)
                }
                continue
            }

            if (line.firstOrNull()?.isWhitespace() == true) {
                continue
            }

            val colonIndex = line.indexOf(':')
            if (colonIndex <= 0) {
                currentListKey = null
                continue
            }

            sawKeyOrEmpty = true
            val key = line.substring(0, colonIndex).trim()
            val valuePart = stripYamlComment(line.substring(colonIndex + 1)).trim()

            if (valuePart.isEmpty()) {
                currentListKey = key
            } else {
                currentListKey = null
                if (valuePart.startsWith('[') && valuePart.endsWith(']')) {
                    parseYamlInlineArray(valuePart.substring(1, valuePart.length - 1)).forEach { item ->
                        recordFrontmatterEntry(key, item, properties, aliases, tags)
                    }
                } else {
                    val value = unquoteYamlValue(valuePart)
                    if (value.isNotEmpty()) {
                        recordFrontmatterEntry(key, value, properties, aliases, tags)
                    }
                }
            }
        }

        if (!sawKeyOrEmpty) return Frontmatter.EMPTY

        return Frontmatter(
            rawYaml = rawYaml,
            properties = properties,
            aliases = aliases,
            tags = tags,
            endOffset = afterClosingOffset,
        )
    }

    private fun recordFrontmatterEntry(
        key: String,
        value: String,
        properties: MutableList<NoteProperty>,
        aliases: MutableList<String>,
        tags: MutableList<String>,
    ) {
        properties.add(NoteProperty(key = key, value = value))
        when (key.lowercase()) {
            "alias", "aliases" -> {
                value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(aliases::add)
            }
            "tag", "tags" -> {
                value.split(YAML_TAG_DELIMITERS).map { it.trim() }.filter { it.isNotEmpty() }.forEach(tags::add)
            }
        }
    }

    private fun buildSnippet(body: String): String {
        if (body.isBlank()) return ""
        val sb = StringBuilder(SNIPPET_MAX_LENGTH + 16)
        var inCodeFence = false

        for (rawLine in body.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inCodeFence = !inCodeFence
                continue
            }
            if (inCodeFence || trimmed.isEmpty()) continue
            if (trimmed == "---" || trimmed == "***" || trimmed == "___") continue

            val cleaned = cleanLineForSnippet(trimmed)
            if (cleaned.isEmpty()) continue

            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(cleaned)
            if (sb.length >= SNIPPET_MAX_LENGTH) break
        }

        val collapsed = sb.toString().replace(MULTI_SPACE_REGEX, " ").trim()
        return if (collapsed.length > SNIPPET_MAX_LENGTH) {
            collapsed.substring(0, SNIPPET_MAX_LENGTH).trimEnd() + "…"
        } else {
            collapsed
        }
    }

    private fun cleanLineForSnippet(line: String): String {
        var result = line
            .replace(OBSIDIAN_COMMENT_INLINE_REGEX, "")
            .replace(EMBED_REGEX, "")
            .replace(WIKILINK_DISPLAY_REGEX) { match ->
                val (_, alias) = splitWikilinkAlias(match.groupValues[1])
                alias ?: match.groupValues[1].substringBefore('#').substringAfterLast('/')
            }
            .replace(MD_LINK_REGEX, "$1")
            .replace(LEADING_MD_MARKERS_REGEX, "")
            .replace(INLINE_FORMAT_CHARS_REGEX, "")
            .trim()
        result = result.replace(MULTI_SPACE_REGEX, " ")
        return result
    }

    private fun parseDueDate(text: String): LocalDate? {
        val raw = DUE_DATE_REGEX.find(text)?.let { match ->
            match.groupValues[1].ifEmpty { match.groupValues[2] }
        } ?: return null
        return runCatching { LocalDate.parse(raw) }.getOrNull()
    }

    private fun splitWikilinkAlias(inner: String): Pair<String, String?> {
        var i = 0
        while (i < inner.length) {
            if (inner[i] == '|') {
                val target = if (i > 0 && inner[i - 1] == '\\') {
                    inner.substring(0, i - 1)
                } else {
                    inner.substring(0, i)
                }
                val alias = inner.substring(i + 1).trim()
                return target to alias
            }
            i++
        }
        return inner to null
    }

    private fun normalizeTag(raw: String): String? {
        val cleaned = raw.trim().removePrefix("#").trimEnd('/')
        if (cleaned.isEmpty()) return null
        if (!TAG_HAS_NON_DIGIT_REGEX.containsMatchIn(cleaned)) return null
        return cleaned.lowercase()
    }

    private fun headingLevel(type: IElementType): Int? = when (type) {
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> 1
        MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> 2
        MarkdownElementTypes.ATX_3 -> 3
        MarkdownElementTypes.ATX_4 -> 4
        MarkdownElementTypes.ATX_5 -> 5
        MarkdownElementTypes.ATX_6 -> 6
        else -> null
    }

    private fun stripYamlComment(value: String): String {
        var inSingle = false
        var inDouble = false
        for (i in value.indices) {
            when (val ch = value[i]) {
                '\'' -> if (!inDouble) inSingle = !inSingle
                '"' -> if (!inSingle && (i == 0 || value[i - 1] != '\\')) inDouble = !inDouble
                '#' -> if (!inSingle && !inDouble && (i == 0 || value[i - 1].isWhitespace())) {
                    return value.substring(0, i)
                }
            }
        }
        return value
    }

    private fun parseYamlInlineArray(inner: String): List<String> {
        val items = ArrayList<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false

        for (i in inner.indices) {
            val ch = inner[i]
            when {
                ch == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    current.append(ch)
                }
                ch == '"' && !inSingle && (i == 0 || inner[i - 1] != '\\') -> {
                    inDouble = !inDouble
                    current.append(ch)
                }
                ch == ',' && !inSingle && !inDouble -> {
                    unquoteYamlValue(current.toString().trim()).takeIf { it.isNotEmpty() }?.let(items::add)
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        unquoteYamlValue(current.toString().trim()).takeIf { it.isNotEmpty() }?.let(items::add)
        return items
    }

    private fun unquoteYamlValue(raw: String): String {
        if (raw.length >= 2) {
            if (raw.startsWith('"') && raw.endsWith('"')) {
                return raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            }
            if (raw.startsWith('\'') && raw.endsWith('\'')) {
                return raw.substring(1, raw.length - 1).replace("''", "'")
            }
        }
        return raw
    }

    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.size <= 1) return ranges
        val sorted = ranges.sortedBy { it.first }
        val merged = ArrayList<IntRange>(sorted.size)
        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.first <= current.last + 1) {
                if (next.last > current.last) {
                    current = current.first..next.last
                }
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    private fun isExcluded(offset: Int, sortedRanges: List<IntRange>): Boolean {
        var low = 0
        var high = sortedRanges.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val range = sortedRanges[mid]
            when {
                offset < range.first -> high = mid - 1
                offset > range.last -> low = mid + 1
                else -> return true
            }
        }
        return false
    }

    private fun findLineEnd(text: String, from: Int): Int {
        val idx = text.indexOf('\n', from)
        return if (idx < 0) text.length else idx
    }

    private fun nextLineStart(text: String, lineEnd: Int): Int =
        if (lineEnd < text.length && text[lineEnd] == '\n') lineEnd + 1 else text.length

    private data class RawTask(
        val offset: Int,
        val lineNumber: Int,
        val text: String,
        val rawText: String,
        val rawTextOffset: Int,
        val statusChar: Char,
        val isCompleted: Boolean,
        val dueDate: LocalDate?,
    )

    private class LineIndex(text: String) {
        private val lineStarts: IntArray

        init {
            var count = 1
            for (ch in text) {
                if (ch == '\n') count++
            }
            val starts = IntArray(count)
            var idx = 1
            for (i in text.indices) {
                if (text[i] == '\n') {
                    starts[idx++] = i + 1
                }
            }
            lineStarts = starts
        }

        fun lineOf(offset: Int): Int {
            var low = 0
            var high = lineStarts.lastIndex
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (lineStarts[mid] <= offset) {
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return low.coerceAtLeast(1)
        }
    }

    private companion object {
        const val SNIPPET_MAX_LENGTH = 200

        val TRAILING_ATX_HASHES = Regex("""\s+#+\s*$""")
        val TASK_LINE_REGEX = Regex("""^\s*(?:[-*+]|\d+[.)])\s+\[(.)](?:\s+(.*)|\s*)$""")
        val WIKILINK_REGEX = Regex("""(!?)\[\[([^\[\]\r\n]+)]]""")
        val EMBED_REGEX = Regex("""!\[\[([^\[\]\r\n]+)]]""")
        val WIKILINK_DISPLAY_REGEX = Regex("""\[\[([^\[\]\r\n]+)]]""")
        val INLINE_TAG_REGEX = Regex("""(?:^|\s)#([\p{L}\p{N}_/-]+)""")
        val TAG_HAS_NON_DIGIT_REGEX = Regex("""[\p{L}_-]""")
        val YAML_TAG_DELIMITERS = Regex("""[\s,]+""")
        val DUE_DATE_REGEX = Regex("""(?:📅\s*(\d{4}-\d{2}-\d{2})|\[due::\s*(\d{4}-\d{2}-\d{2})])""")
        val OBSIDIAN_COMMENT_INLINE_REGEX = Regex("""%%.*?%%""")
        val MD_LINK_REGEX = Regex("""\[([^\]]+)]\([^)]+\)""")
        val LEADING_MD_MARKERS_REGEX = Regex("""^(?:#{1,6}\s+|>\s+|(?:[-*+]|\d+[.)])\s+(?:\[.\]\s+)?)""")
        val INLINE_FORMAT_CHARS_REGEX = Regex("""[*_~`]+""")
        val MULTI_SPACE_REGEX = Regex("""\s+""")
    }
}
