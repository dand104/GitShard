package io.gitshard.gitshard.data

import io.gitshard.gitshard.core.AppDispatchers
import io.gitshard.gitshard.core.AtomicFileWriter
import io.gitshard.gitshard.core.VaultPath
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

data class VaultFileEntry(
    val path: VaultPath,
    val lastModified: Long,
    val fileSize: Long,
)

data class VaultScanResult(
    val markdownFiles: List<VaultFileEntry>,
    val allPaths: List<VaultPath>,
)

class VaultStorage(
    private val dispatchers: AppDispatchers = AppDispatchers(),
) {
    suspend fun scanVault(vaultRoot: File): VaultScanResult = withContext(dispatchers.io) {
        if (!vaultRoot.exists() || !vaultRoot.isDirectory) {
            return@withContext VaultScanResult(emptyList(), emptyList())
        }
        val rootPath = vaultRoot.canonicalFile.toPath()
        val markdownFiles = ArrayList<VaultFileEntry>()
        val allPaths = ArrayList<VaultPath>()

        Files.walkFileTree(
            rootPath,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir == rootPath) return FileVisitResult.CONTINUE
                    val name = dir.fileName?.toString().orEmpty()
                    if (name.startsWith('.')) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    val name = file.fileName?.toString().orEmpty()
                    if (name.startsWith('.')) return FileVisitResult.CONTINUE

                    val relative = rootPath.relativize(file).toString()
                    val vaultPath = VaultPath.ofOrNull(relative) ?: return FileVisitResult.CONTINUE
                    allPaths.add(vaultPath)
                    if (vaultPath.isMarkdown) {
                        markdownFiles.add(
                            VaultFileEntry(
                                path = vaultPath,
                                lastModified = attrs.lastModifiedTime().toMillis(),
                                fileSize = attrs.size(),
                            ),
                        )
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            },
        )

        VaultScanResult(markdownFiles = markdownFiles, allPaths = allPaths)
    }

    suspend fun stat(vaultRoot: File, path: VaultPath): VaultFileEntry? = withContext(dispatchers.io) {
        val file = path.resolveIn(vaultRoot)
        if (!file.exists() || !file.isFile) return@withContext null
        VaultFileEntry(
            path = path,
            lastModified = file.lastModified(),
            fileSize = file.length(),
        )
    }

    suspend fun exists(vaultRoot: File, path: VaultPath): Boolean = withContext(dispatchers.io) {
        val file = path.resolveIn(vaultRoot)
        file.exists() && file.isFile
    }

    suspend fun readText(vaultRoot: File, path: VaultPath): String = withContext(dispatchers.io) {
        path.resolveIn(vaultRoot).readText(Charsets.UTF_8)
    }

    suspend fun readTextOrNull(vaultRoot: File, path: VaultPath): String? = withContext(dispatchers.io) {
        val file = path.resolveIn(vaultRoot)
        if (!file.exists() || !file.isFile) null else file.readText(Charsets.UTF_8)
    }

    suspend fun writeText(
        vaultRoot: File,
        path: VaultPath,
        content: String,
    ): VaultFileEntry = withContext(dispatchers.io) {
        val target = path.resolveIn(vaultRoot)
        AtomicFileWriter.writeText(target, content)
        VaultFileEntry(
            path = path,
            lastModified = target.lastModified(),
            fileSize = target.length(),
        )
    }

    suspend fun rename(
        vaultRoot: File,
        from: VaultPath,
        to: VaultPath,
    ): VaultFileEntry = withContext(dispatchers.io) {
        val source = from.resolveIn(vaultRoot)
        require(source.exists() && source.isFile) { "Source file does not exist: ${from.value}" }
        if (from == to) {
            return@withContext VaultFileEntry(to, source.lastModified(), source.length())
        }
        val target = to.resolveIn(vaultRoot)
        val sameCaseInsensitiveTarget = from.value.equals(to.value, ignoreCase = true)
        require(!target.exists() || sameCaseInsensitiveTarget) {
            "Target file already exists: ${to.value}"
        }
        target.parentFile?.let { Files.createDirectories(it.toPath()) }

        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
        VaultFileEntry(
            path = to,
            lastModified = target.lastModified(),
            fileSize = target.length(),
        )
    }

    suspend fun delete(vaultRoot: File, path: VaultPath): Boolean = withContext(dispatchers.io) {
        Files.deleteIfExists(path.resolveIn(vaultRoot).toPath())
    }

    suspend fun createFolder(vaultRoot: File, folderPath: VaultPath) = withContext(dispatchers.io) {
        Files.createDirectories(folderPath.resolveIn(vaultRoot).toPath())
        Unit
    }
}
