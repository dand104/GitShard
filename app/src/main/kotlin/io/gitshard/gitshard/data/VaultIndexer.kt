package io.gitshard.gitshard.data

import androidx.room.withTransaction
import io.gitshard.gitshard.core.AppDispatchers
import io.gitshard.gitshard.core.VaultPath
import io.gitshard.gitshard.data.db.VaultDatabase
import io.gitshard.gitshard.data.db.entity.NoteEntity
import io.gitshard.gitshard.data.db.entity.NoteFtsEntity
import io.gitshard.gitshard.data.db.entity.NoteLinkEntity
import io.gitshard.gitshard.data.db.entity.NotePropertyEntity
import io.gitshard.gitshard.data.db.entity.NoteTagEntity
import io.gitshard.gitshard.data.db.entity.NoteTaskEntity
import io.gitshard.gitshard.markdown.BaseParser
import io.gitshard.gitshard.markdown.LinkResolution
import io.gitshard.gitshard.markdown.VaultPathIndex
import io.gitshard.gitshard.markdown.WikilinkResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class IndexSyncReport(
    val indexedCount: Int = 0,
    val deletedCount: Int = 0,
    val unchangedCount: Int = 0,
    val relinkedCount: Int = 0,
)

class VaultIndexer(
    private val database: VaultDatabase,
    private val storage: VaultStorage,
    private val parser: BaseParser = BaseParser(),
    private val dispatchers: AppDispatchers = AppDispatchers(),
) {
    private val vaultLocks = ConcurrentHashMap<String, Mutex>()
    private val cachedPathsByVault = ConcurrentHashMap<String, MutableSet<VaultPath>>()
    private val noteDao = database.noteDao()
    private val graphDao = database.graphDao()

    suspend fun syncWithDisk(vaultId: String, vaultRoot: File): IndexSyncReport =
        withVaultLock(vaultId) {
            val scan = storage.scanVault(vaultRoot)
            val diskAllPaths = scan.allPaths.toHashSet()
            val previousAllPaths = cachedPathsByVault[vaultId]
            val pathSetChanged = previousAllPaths == null || previousAllPaths != diskAllPaths

            val existingByPath = noteDao.getSyncMeta(vaultId).associateBy { it.path }
            val diskByPath = scan.markdownFiles.associateBy { it.path.value }

            val deletedPaths = existingByPath.keys.filter { it !in diskByPath }
            val changedEntries = ArrayList<VaultFileEntry>()
            var addedCount = 0
            var unchangedCount = 0

            for (entry in scan.markdownFiles) {
                val cached = existingByPath[entry.path.value]
                if (cached == null) {
                    addedCount++
                    changedEntries.add(entry)
                } else if (cached.lastModified != entry.lastModified || cached.fileSize != entry.fileSize) {
                    changedEntries.add(entry)
                } else {
                    unchangedCount++
                }
            }

            val pathIndex = VaultPathIndex(scan.allPaths)
            val prepared = changedEntries.mapNotNull { entry ->
                val content = storage.readTextOrNull(vaultRoot, entry.path) ?: return@mapNotNull null
                prepareNoteData(vaultId, entry, content, pathIndex)
            }
            val shouldReconcileLinks = addedCount > 0 || deletedPaths.isNotEmpty() || pathSetChanged

            val relinkedCount = commitBatch(
                vaultId = vaultId,
                toDelete = deletedPaths,
                prepared = prepared,
                pathIndex = pathIndex,
                reconcileLinks = shouldReconcileLinks,
            )
            cachedPathsByVault[vaultId] = diskAllPaths.toMutableSet()

            IndexSyncReport(
                indexedCount = prepared.size,
                deletedCount = deletedPaths.size,
                unchangedCount = unchangedCount,
                relinkedCount = relinkedCount,
            )
        }

    suspend fun indexSingleFile(
        vaultId: String,
        vaultRoot: File,
        path: VaultPath,
        knownContent: String? = null,
    ): IndexSyncReport = withVaultLock(vaultId) {
        if (path.isHidden) return@withVaultLock IndexSyncReport()

        val wasCold = !cachedPathsByVault.containsKey(vaultId)
        val nextPaths = getOrScanPathsSnapshot(vaultId, vaultRoot)
        val entry = storage.stat(vaultRoot, path)
        val cacheMutated = if (entry == null) nextPaths.remove(path) else nextPaths.add(path)
        val pathIndex = VaultPathIndex(nextPaths)

        if (!path.isMarkdown) {
            val relinked = if (wasCold || cacheMutated) {
                commitBatch(
                    vaultId = vaultId,
                    toDelete = emptyList(),
                    prepared = emptyList(),
                    pathIndex = pathIndex,
                    reconcileLinks = true,
                )
            } else {
                0
            }
            cachedPathsByVault[vaultId] = nextPaths
            return@withVaultLock IndexSyncReport(relinkedCount = relinked)
        }

        val existedInDb = noteDao.getFtsRowId(vaultId, path.value) != null
        if (entry == null) {
            if (!existedInDb) {
                cachedPathsByVault[vaultId] = nextPaths
                return@withVaultLock IndexSyncReport()
            }
            val relinked = commitBatch(
                vaultId = vaultId,
                toDelete = listOf(path.value),
                prepared = emptyList(),
                pathIndex = pathIndex,
                reconcileLinks = true,
            )
            cachedPathsByVault[vaultId] = nextPaths
            return@withVaultLock IndexSyncReport(deletedCount = 1, relinkedCount = relinked)
        }

        val content = knownContent ?: storage.readTextOrNull(vaultRoot, path)
            ?: return@withVaultLock IndexSyncReport()
        val prepared = prepareNoteData(vaultId, entry, content, pathIndex)
        val relinked = commitBatch(
            vaultId = vaultId,
            toDelete = emptyList(),
            prepared = listOf(prepared),
            pathIndex = pathIndex,
            reconcileLinks = !existedInDb || cacheMutated,
        )
        cachedPathsByVault[vaultId] = nextPaths

        IndexSyncReport(indexedCount = 1, relinkedCount = relinked)
    }

    suspend fun reindexPaths(
        vaultId: String,
        vaultRoot: File,
        paths: Collection<VaultPath>,
    ): IndexSyncReport = withVaultLock(vaultId) {
        val visiblePaths = paths.filterNot { it.isHidden }.distinct()
        if (visiblePaths.isEmpty()) return@withVaultLock IndexSyncReport()

        val wasCold = !cachedPathsByVault.containsKey(vaultId)
        val nextPaths = getOrScanPathsSnapshot(vaultId, vaultRoot)
        val entriesByPath = LinkedHashMap<VaultPath, VaultFileEntry?>(visiblePaths.size)
        var topologyChanged = wasCold

        for (path in visiblePaths) {
            val entry = storage.stat(vaultRoot, path)
            entriesByPath[path] = entry
            val mutated = if (entry == null) nextPaths.remove(path) else nextPaths.add(path)
            if (mutated) topologyChanged = true
        }

        val pathIndex = VaultPathIndex(nextPaths)
        val existingPathsInDb = noteDao.getAllPaths(vaultId).toHashSet()
        val toDelete = ArrayList<String>()
        val prepared = ArrayList<PreparedNoteIndex>()

        for ((path, entry) in entriesByPath) {
            if (!path.isMarkdown) continue
            val existed = path.value in existingPathsInDb
            if (entry == null) {
                if (existed) {
                    toDelete.add(path.value)
                    topologyChanged = true
                }
            } else {
                if (!existed) topologyChanged = true
                val content = storage.readTextOrNull(vaultRoot, path) ?: continue
                prepared.add(prepareNoteData(vaultId, entry, content, pathIndex))
            }
        }

        val relinked = commitBatch(
            vaultId = vaultId,
            toDelete = toDelete,
            prepared = prepared,
            pathIndex = pathIndex,
            reconcileLinks = topologyChanged,
        )
        cachedPathsByVault[vaultId] = nextPaths

        IndexSyncReport(
            indexedCount = prepared.size,
            deletedCount = toDelete.size,
            relinkedCount = relinked,
        )
    }

    suspend fun clearVaultIndex(vaultId: String) = withVaultLock(vaultId) {
        cachedPathsByVault.remove(vaultId)
        noteDao.deleteVaultIndex(vaultId)
    }

    private suspend fun getOrScanPathsSnapshot(vaultId: String, vaultRoot: File): MutableSet<VaultPath> {
        cachedPathsByVault[vaultId]?.let { return it.toMutableSet() }
        return storage.scanVault(vaultRoot).allPaths.toMutableSet()
    }

    private suspend fun commitBatch(
        vaultId: String,
        toDelete: List<String>,
        prepared: List<PreparedNoteIndex>,
        pathIndex: VaultPathIndex,
        reconcileLinks: Boolean,
    ): Int {
        if (toDelete.isEmpty() && prepared.isEmpty() && !reconcileLinks) return 0

        val reindexedSet = prepared.mapTo(HashSet(prepared.size)) { it.note.path }
        var relinkedCount = 0
        database.withTransaction {
            for (deletedPath in toDelete) {
                noteDao.deleteNote(vaultId, deletedPath)
            }
            for (data in prepared) {
                noteDao.replaceNoteIndex(
                    note = data.note,
                    fts = data.fts,
                    links = data.links,
                    tags = data.tags,
                    tasks = data.tasks,
                    properties = data.properties,
                )
            }
            if (reconcileLinks) {
                relinkedCount = reconcileLinksInTransaction(
                    vaultId = vaultId,
                    pathIndex = pathIndex,
                    skipSourcePaths = reindexedSet,
                )
            }
        }
        return relinkedCount
    }

    private suspend fun prepareNoteData(
        vaultId: String,
        entry: VaultFileEntry,
        content: String,
        pathIndex: VaultPathIndex,
    ): PreparedNoteIndex = withContext(dispatchers.default) {
        val parsed = parser.parse(content)
        val title = parsed.title?.takeIf { it.isNotBlank() } ?: entry.path.nameWithoutExtension
        val wordCount = countWords(parsed.searchableBody)

        val note = NoteEntity(
            vaultId = vaultId,
            path = entry.path.value,
            folderPath = entry.path.folderPath,
            name = entry.path.name,
            title = title,
            snippet = parsed.snippet,
            lastModified = entry.lastModified,
            fileSize = entry.fileSize,
            wordCount = wordCount,
        )

        val fts = NoteFtsEntity(
            path = entry.path.value,
            title = title,
            headings = parsed.headings.joinToString("\n") { it.text },
            aliases = parsed.frontmatter.aliases.joinToString("\n"),
            tags = parsed.tags.joinToString(" "),
            body = parsed.searchableBody,
        )

        val links = parsed.links.map { link ->
            val resolved = resolveTargetPath(entry.path, link.targetNote, link.subpath, pathIndex)
            NoteLinkEntity(
                vaultId = vaultId,
                sourcePath = entry.path.value,
                rawTarget = link.rawTarget,
                targetNote = link.targetNote,
                resolvedTargetPath = resolved,
                subpath = link.subpath,
                alias = link.alias,
                isEmbed = link.isEmbed,
                lineNumber = link.lineNumber,
            )
        }

        val tags = parsed.tags.map { tag ->
            NoteTagEntity(vaultId = vaultId, notePath = entry.path.value, tag = tag)
        }

        val tasks = parsed.tasks.map { task ->
            NoteTaskEntity(
                vaultId = vaultId,
                notePath = entry.path.value,
                lineNumber = task.lineNumber,
                text = task.text,
                statusChar = task.statusChar.toString(),
                isCompleted = task.isCompleted,
                dueDate = task.dueDate?.toString(),
                tagsPacked = NoteTaskEntity.packTags(task.tags),
            )
        }

        val properties = parsed.frontmatter.properties.map { prop ->
            NotePropertyEntity(
                vaultId = vaultId,
                notePath = entry.path.value,
                key = prop.key,
                keyLower = prop.key.lowercase(),
                value = prop.value,
            )
        }

        PreparedNoteIndex(note, fts, links, tags, tasks, properties)
    }

    private suspend fun reconcileLinksInTransaction(
        vaultId: String,
        pathIndex: VaultPathIndex,
        skipSourcePaths: Set<String>,
    ): Int {
        val allLinks = graphDao.getAllLinks(vaultId)
        var updated = 0
        for (link in allLinks) {
            if (link.sourcePath in skipSourcePaths) continue
            val source = VaultPath.ofOrNull(link.sourcePath) ?: continue
            val resolved = resolveTargetPath(source, link.targetNote, link.subpath, pathIndex)
            if (resolved != link.resolvedTargetPath) {
                graphDao.updateResolvedTarget(link.id, resolved)
                updated++
            }
        }
        return updated
    }

    private fun resolveTargetPath(
        sourcePath: VaultPath,
        targetNote: String,
        subpath: String?,
        pathIndex: VaultPathIndex,
    ): String? = when (val resolution = WikilinkResolver.resolve(sourcePath, targetNote, subpath, pathIndex)) {
        is LinkResolution.Resolved -> resolution.path.value
        is LinkResolution.SelfHeading -> resolution.path.value
        is LinkResolution.Unresolved -> null
    }

    private fun countWords(text: String): Int {
        var count = 0
        var inWord = false
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                if (!inWord) {
                    count++
                    inWord = true
                }
            } else {
                inWord = false
            }
        }
        return count
    }

    private suspend fun <T> withVaultLock(vaultId: String, block: suspend () -> T): T {
        val mutex = vaultLocks.computeIfAbsent(vaultId) { Mutex() }
        return mutex.withLock { block() }
    }

    private data class PreparedNoteIndex(
        val note: NoteEntity,
        val fts: NoteFtsEntity,
        val links: List<NoteLinkEntity>,
        val tags: List<NoteTagEntity>,
        val tasks: List<NoteTaskEntity>,
        val properties: List<NotePropertyEntity>,
    )
}
