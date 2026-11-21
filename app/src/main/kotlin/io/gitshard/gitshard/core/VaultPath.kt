package io.gitshard.gitshard.core

import java.io.File
import java.nio.file.Path

@JvmInline
value class VaultPath private constructor(val value: String) : Comparable<VaultPath> {

    val name: String
        get() = value.substringAfterLast('/')

    val nameWithoutExtension: String
        get() {
            val fileName = name
            val dot = fileName.lastIndexOf('.')
            return if (dot > 0) fileName.substring(0, dot) else fileName
        }

    val extension: String
        get() {
            val fileName = name
            val dot = fileName.lastIndexOf('.')
            return if (dot > 0 && dot < fileName.lastIndex) {
                fileName.substring(dot + 1).lowercase()
            } else {
                ""
            }
        }

    val parent: VaultPath?
        get() {
            val slash = value.lastIndexOf('/')
            return if (slash > 0) VaultPath(value.substring(0, slash)) else null
        }

    val folderPath: String
        get() = parent?.value.orEmpty()

    val isMarkdown: Boolean
        get() = extension == "md"

    val isHidden: Boolean
        get() = value.split('/').any { it.startsWith('.') }

    fun resolve(child: String): VaultPath = of("$value/$child")

    /**
     * Allows a path relative to [vaultRoot] with a check that the resulting file
     * physically remains inside [vaultRoot].
     */
    fun resolveIn(vaultRoot: File): File {
        val rootCanonical = vaultRoot.canonicalFile
        val targetCanonical = File(rootCanonical, value).canonicalFile
        val rootPrefix = rootCanonical.path.let { if (it.endsWith(File.separatorChar)) it else it + File.separatorChar }
        require(targetCanonical.path.startsWith(rootPrefix)) {
            "Path escapes vault root: $value"
        }
        return targetCanonical
    }

    fun resolveIn(vaultRoot: Path): Path = resolveIn(vaultRoot.toFile()).toPath()

    override fun compareTo(other: VaultPath): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        fun of(raw: String): VaultPath =
            ofOrNull(raw) ?: throw IllegalArgumentException("Invalid vault path: '$raw'")

        fun ofOrNull(raw: String): VaultPath? {
            if (raw.isEmpty() || raw.indexOf('\u0000') >= 0) return null

            val normalized = raw.replace('\\', '/')
            val segments = ArrayList<String>()

            for (segment in normalized.split('/')) {
                when (segment) {
                    "", "." -> continue
                    ".." -> return null
                    else -> segments.add(segment)
                }
            }

            if (segments.isEmpty()) return null
            return VaultPath(segments.joinToString("/"))
        }

        /**
         * Builds the relative [VaultPath] for a file inside [vaultRoot].
         * Returns `null` if the file is outside [vaultRoot] or matches the root itself.
         */
        fun relativizeOrNull(vaultRoot: File, file: File): VaultPath? {
            val rootPath = vaultRoot.canonicalFile.toPath()
            val targetPath = file.canonicalFile.toPath()
            if (!targetPath.startsWith(rootPath) || targetPath == rootPath) return null
            return ofOrNull(rootPath.relativize(targetPath).toString())
        }
    }
}
