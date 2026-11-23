package io.gitshard.gitshard.markdown

import io.gitshard.gitshard.core.VaultPath

sealed interface LinkResolution {
    data class Resolved(val path: VaultPath, val subpath: String?) : LinkResolution
    data class SelfHeading(val path: VaultPath, val subpath: String) : LinkResolution
    data class Unresolved(val suggestedPath: VaultPath, val subpath: String?) : LinkResolution
}

class VaultPathIndex(paths: Collection<VaultPath>) {
    private val byExactLower: Map<String, VaultPath>
    private val byNoExtLower: Map<String, VaultPath>
    private val byFileNameLower: Map<String, List<VaultPath>>
    private val byStemLower: Map<String, List<VaultPath>>

    init {
        val exact = HashMap<String, VaultPath>(paths.size)
        val noExt = HashMap<String, VaultPath>(paths.size)
        val fileName = HashMap<String, MutableList<VaultPath>>()
        val stem = HashMap<String, MutableList<VaultPath>>()

        for (path in paths) {
            exact[path.value.lowercase()] = path
            if (path.isMarkdown) {
                noExt[path.value.removeSuffix(".md").lowercase()] = path
                stem.getOrPut(path.nameWithoutExtension.lowercase()) { ArrayList() }.add(path)
            }
            fileName.getOrPut(path.name.lowercase()) { ArrayList() }.add(path)
        }

        byExactLower = exact
        byNoExtLower = noExt
        byFileNameLower = fileName
        byStemLower = stem
    }

    internal fun findExact(lowerPath: String): VaultPath? =
        byExactLower[lowerPath] ?: byNoExtLower[lowerPath]

    internal fun findBySuffix(lowerSuffix: String): List<VaultPath> {
        val slashSuffix = "/$lowerSuffix"
        val slashSuffixMd = "/$lowerSuffix.md"
        return byExactLower.entries.mapNotNull { (key, path) ->
            if (key.endsWith(slashSuffix) || key.endsWith(slashSuffixMd)) path else null
        }
    }

    internal fun findByShortName(lowerName: String): List<VaultPath> =
        byFileNameLower[lowerName] ?: byStemLower[lowerName].orEmpty()
}

object WikilinkResolver {

    fun resolve(
        sourceNote: VaultPath,
        link: NoteLink,
        index: VaultPathIndex,
    ): LinkResolution = resolve(
        sourceNote = sourceNote,
        targetNote = link.targetNote,
        subpath = link.subpath,
        index = index,
    )

    fun resolve(
        sourceNote: VaultPath,
        targetNote: String,
        subpath: String? = null,
        index: VaultPathIndex,
    ): LinkResolution {
        val trimmedTarget = targetNote.trim()
        if (trimmedTarget.isEmpty()) {
            return if (subpath != null) {
                LinkResolution.SelfHeading(sourceNote, subpath)
            } else {
                LinkResolution.Unresolved(sourceNote, null)
            }
        }

        val posixTarget = trimmedTarget.replace('\\', '/')

        if (posixTarget.startsWith("./") || posixTarget.startsWith("../")) {
            val resolvedRelative = resolveRelative(sourceNote.parent, posixTarget)
            if (resolvedRelative != null) {
                val match = index.findExact(resolvedRelative.value.lowercase())
                if (match != null) return LinkResolution.Resolved(match, subpath)
                return LinkResolution.Unresolved(ensureExtension(resolvedRelative), subpath)
            }
        }

        val isExplicitRoot = posixTarget.startsWith("/")
        val normalized = posixTarget.trimStart('/').trimEnd('/')
        val lower = normalized.lowercase()

        if (isExplicitRoot || '/' in lower) {
            index.findExact(lower)?.let { return LinkResolution.Resolved(it, subpath) }

            if (!isExplicitRoot) {
                val suffixMatches = index.findBySuffix(lower)
                if (suffixMatches.isNotEmpty()) {
                    return LinkResolution.Resolved(pickClosest(sourceNote, suffixMatches), subpath)
                }
            }
        } else {
            val shortMatches = index.findByShortName(lower)
            if (shortMatches.isNotEmpty()) {
                return LinkResolution.Resolved(pickClosest(sourceNote, shortMatches), subpath)
            }
        }

        val suggestedBase = VaultPath.ofOrNull(normalized)
            ?: sourceNote.parent?.resolve(normalized.substringAfterLast('/'))
            ?: VaultPath.of(normalized.substringAfterLast('/').ifEmpty { "Untitled" })
        return LinkResolution.Unresolved(ensureExtension(suggestedBase), subpath)
    }

    private fun resolveRelative(baseFolder: VaultPath?, relative: String): VaultPath? {
        val stack = ArrayList<String>()
        if (baseFolder != null) {
            stack.addAll(baseFolder.value.split('/'))
        }
        for (segment in relative.split('/')) {
            when (segment) {
                "", "." -> continue
                ".." -> {
                    if (stack.isEmpty()) return null
                    stack.removeAt(stack.lastIndex)
                }
                else -> stack.add(segment)
            }
        }
        if (stack.isEmpty()) return null
        return VaultPath.ofOrNull(stack.joinToString("/"))
    }

    private fun ensureExtension(path: VaultPath): VaultPath {
        if (path.isMarkdown || path.extension in KNOWN_NON_MARKDOWN_EXTENSIONS) {
            return path
        }
        return VaultPath.of("${path.value}.md")
    }

    private fun pickClosest(source: VaultPath, candidates: List<VaultPath>): VaultPath {
        if (candidates.size == 1) return candidates[0]
        val sourceSegments = source.parent?.value?.split('/').orEmpty()

        return candidates.minWith(
            compareBy<VaultPath>(
                { it.parent != source.parent },
                { folderDistance(sourceSegments, it.parent?.value?.split('/').orEmpty()) },
                { it.value.length },
                { it.value },
            ),
        )
    }

    private fun folderDistance(a: List<String>, b: List<String>): Int {
        var common = 0
        val limit = minOf(a.size, b.size)
        while (common < limit && a[common].equals(b[common], ignoreCase = true)) {
            common++
        }
        return (a.size - common) + (b.size - common)
    }

    private val KNOWN_NON_MARKDOWN_EXTENSIONS = setOf(
        "canvas", "base", "pdf", "txt", "json", "csv",
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "avif",
        "mp3", "wav", "m4a", "ogg", "flac", "3gp",
        "mp4", "webm", "mov", "mkv", "ogv",
    )
}
