package io.gitshard.gitshard.git

import io.gitshard.gitshard.core.VaultPath
import java.io.File

sealed interface ConflictSegment {
    data class Context(
        val lines: List<String>,
    ) : ConflictSegment {
        fun text(lineSeparator: String = "\n"): String = lines.joinToString(lineSeparator)
    }

    data class Hunk(
        val id: Int,
        val oursLabel: String,
        val oursLines: List<String>,
        val baseLabel: String?,
        val baseLines: List<String>?,
        val theirsLabel: String,
        val theirsLines: List<String>,
    ) : ConflictSegment {
        fun oursText(lineSeparator: String = "\n"): String = oursLines.joinToString(lineSeparator)
        fun baseText(lineSeparator: String = "\n"): String? = baseLines?.joinToString(lineSeparator)
        fun theirsText(lineSeparator: String = "\n"): String = theirsLines.joinToString(lineSeparator)
    }
}

sealed interface ConflictChoice {
    data object Ours : ConflictChoice
    data object Theirs : ConflictChoice
    data object BothOursFirst : ConflictChoice
    data object BothTheirsFirst : ConflictChoice
    data class Custom(val text: String) : ConflictChoice
}

data class ParsedConflictDocument(
    val segments: List<ConflictSegment>,
    val lineSeparator: String,
    val trailingNewline: Boolean,
) {
    val hunks: List<ConflictSegment.Hunk>
        get() = segments.filterIsInstance<ConflictSegment.Hunk>()

    val hasConflicts: Boolean
        get() = hunks.isNotEmpty()
}

object ConflictParser {

    private const val OURS_MARKER = "<<<<<<<"
    private const val BASE_MARKER = "|||||||"
    private const val SEP_MARKER = "======="
    private const val THEIRS_MARKER = ">>>>>>>"
    private const val BINARY_SAMPLE_BYTES = 8 * 1024
    const val DEFAULT_MAX_FILE_BYTES: Long = 2L * 1024 * 1024

    fun loadFile(
        repoDir: File,
        path: VaultPath,
        maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    ): ConflictFile {
        val file = path.resolveIn(repoDir)
        if (!file.exists() || !file.isFile) {
            return ConflictFile(path = path, kind = ConflictKind.DELETED_OR_MISSING)
        }
        if (file.length() > maxFileBytes) {
            return ConflictFile(path = path, kind = ConflictKind.BINARY_OR_UNPARSEABLE)
        }
        if (looksBinary(file)) {
            return ConflictFile(path = path, kind = ConflictKind.BINARY_OR_UNPARSEABLE)
        }
        val text = file.readText(Charsets.UTF_8)
        val parsed = parse(text)
        return if (parsed.hasConflicts) {
            ConflictFile(path = path, kind = ConflictKind.CONTENT, document = parsed)
        } else {
            ConflictFile(path = path, kind = ConflictKind.BINARY_OR_UNPARSEABLE)
        }
    }

    fun loadFiles(
        repoDir: File,
        paths: Collection<VaultPath>,
        maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    ): List<ConflictFile> =
        paths.sorted().map { path -> loadFile(repoDir, path, maxFileBytes) }

    fun hasConflictMarkers(content: String): Boolean {
        var sawOurs = false
        var sawSep = false
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            when {
                isOursMarker(line) -> sawOurs = true
                sawOurs && line == SEP_MARKER -> sawSep = true
                sawSep && isTheirsMarker(line) -> return true
            }
        }
        return false
    }

    fun parse(content: String): ParsedConflictDocument {
        val lineSeparator = if ("\r\n" in content) "\r\n" else "\n"
        val trailingNewline = content.endsWith("\n")
        if (content.isEmpty()) {
            return ParsedConflictDocument(
                segments = emptyList(),
                lineSeparator = lineSeparator,
                trailingNewline = false,
            )
        }

        val lines = splitLinesPreservingStructure(content, trailingNewline)
        val segments = ArrayList<ConflictSegment>()
        val contextBuffer = ArrayList<String>()
        var nextHunkId = 0

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!isOursMarker(line)) {
                contextBuffer.add(line)
                i++
                continue
            }

            val parsed = tryParseHunk(lines, startIndex = i, hunkId = nextHunkId)
            if (parsed == null) {
                contextBuffer.add(line)
                i++
                continue
            }

            if (contextBuffer.isNotEmpty()) {
                segments.add(ConflictSegment.Context(contextBuffer.toList()))
                contextBuffer.clear()
            }
            segments.add(parsed.hunk)
            nextHunkId++
            i = parsed.nextIndex
        }

        if (contextBuffer.isNotEmpty()) {
            segments.add(ConflictSegment.Context(contextBuffer.toList()))
        }

        return ParsedConflictDocument(
            segments = segments,
            lineSeparator = lineSeparator,
            trailingNewline = trailingNewline,
        )
    }

    fun resolve(
        document: ParsedConflictDocument,
        choices: Map<Int, ConflictChoice>,
    ): String {
        val outLines = ArrayList<String>()

        for (segment in document.segments) {
            when (segment) {
                is ConflictSegment.Context -> outLines.addAll(segment.lines)
                is ConflictSegment.Hunk -> {
                    val choice = requireNotNull(choices[segment.id]) {
                        "Missing resolution choice for conflict hunk #${segment.id}"
                    }
                    when (choice) {
                        ConflictChoice.Ours -> outLines.addAll(segment.oursLines)
                        ConflictChoice.Theirs -> outLines.addAll(segment.theirsLines)
                        ConflictChoice.BothOursFirst -> {
                            outLines.addAll(segment.oursLines)
                            outLines.addAll(segment.theirsLines)
                        }
                        ConflictChoice.BothTheirsFirst -> {
                            outLines.addAll(segment.theirsLines)
                            outLines.addAll(segment.oursLines)
                        }
                        is ConflictChoice.Custom -> {
                            if (choice.text.isNotEmpty()) {
                                val normalized = choice.text.replace("\r\n", "\n").removeSuffix("\n")
                                outLines.addAll(normalized.split('\n'))
                            }
                        }
                    }
                }
            }
        }

        if (outLines.isEmpty()) return ""
        val joined = outLines.joinToString(document.lineSeparator)
        return if (document.trailingNewline) joined + document.lineSeparator else joined
    }

    private data class HunkParseResult(
        val hunk: ConflictSegment.Hunk,
        val nextIndex: Int,
    )

    private fun tryParseHunk(
        lines: List<String>,
        startIndex: Int,
        hunkId: Int,
    ): HunkParseResult? {
        val oursLabel = extractLabel(lines[startIndex], OURS_MARKER)
        val oursLines = ArrayList<String>()
        var baseLabel: String? = null
        var baseLines: ArrayList<String>? = null
        val theirsLines = ArrayList<String>()

        var section = HunkSection.OURS
        var idx = startIndex + 1

        while (idx < lines.size) {
            val current = lines[idx]
            when (section) {
                HunkSection.OURS -> when {
                    isBaseMarker(current) -> {
                        baseLabel = extractLabel(current, BASE_MARKER)
                        baseLines = ArrayList()
                        section = HunkSection.BASE
                    }
                    current == SEP_MARKER -> {
                        section = HunkSection.THEIRS
                    }
                    isOursMarker(current) -> return null
                    else -> oursLines.add(current)
                }

                HunkSection.BASE -> when {
                    current == SEP_MARKER -> {
                        section = HunkSection.THEIRS
                    }
                    isOursMarker(current) -> return null
                    else -> baseLines!!.add(current)
                }

                HunkSection.THEIRS -> when {
                    isTheirsMarker(current) -> {
                        val theirsLabel = extractLabel(current, THEIRS_MARKER)
                        return HunkParseResult(
                            hunk = ConflictSegment.Hunk(
                                id = hunkId,
                                oursLabel = oursLabel,
                                oursLines = oursLines,
                                baseLabel = baseLabel,
                                baseLines = baseLines,
                                theirsLabel = theirsLabel,
                                theirsLines = theirsLines,
                            ),
                            nextIndex = idx + 1,
                        )
                    }
                    isOursMarker(current) -> return null
                    else -> theirsLines.add(current)
                }
            }
            idx++
        }

        return null
    }

    private enum class HunkSection { OURS, BASE, THEIRS }

    private fun splitLinesPreservingStructure(content: String, trailingNewline: Boolean): List<String> {
        val normalized = content.replace("\r\n", "\n")
        val trimmed = if (trailingNewline) normalized.dropLast(1) else normalized
        if (trimmed.isEmpty()) return if (trailingNewline) listOf("") else emptyList()
        return trimmed.split('\n')
    }

    private fun isOursMarker(line: String): Boolean =
        line == OURS_MARKER || line.startsWith("$OURS_MARKER ")

    private fun isBaseMarker(line: String): Boolean =
        line == BASE_MARKER || line.startsWith("$BASE_MARKER ")

    private fun isTheirsMarker(line: String): Boolean =
        line == THEIRS_MARKER || line.startsWith("$THEIRS_MARKER ")

    private fun extractLabel(line: String, marker: String): String =
        if (line.length > marker.length) line.substring(marker.length).trim() else ""

    private fun looksBinary(file: File): Boolean {
        val sample = ByteArray(BINARY_SAMPLE_BYTES)
        val bytesRead = file.inputStream().use { input ->
            var total = 0
            while (total < sample.size) {
                val read = input.read(sample, total, sample.size - total)
                if (read <= 0) break
                total += read
            }
            total
        }
        for (i in 0 until bytesRead) {
            if (sample[i] == 0.toByte()) return true
        }
        return false
    }
}
