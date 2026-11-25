package io.gitshard.gitshard.data

import io.gitshard.gitshard.core.VaultPath
import io.gitshard.gitshard.data.prefs.NewFileLocation
import io.gitshard.gitshard.data.prefs.NoteConfigStore
import io.gitshard.gitshard.data.prefs.TemplatesConfig
import io.gitshard.gitshard.data.prefs.VaultConfig
import io.gitshard.gitshard.data.prefs.VaultRegistryStore
import io.gitshard.gitshard.markdown.TaskLineMutator
import io.gitshard.gitshard.markdown.TaskMutationResult
import io.gitshard.gitshard.markdown.TemplateExpander
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

data class DailyNoteResult(
    val path: VaultPath,
    val created: Boolean,
)

class NoteService(
    private val vaultRegistry: VaultRegistryStore,
    private val storage: VaultStorage,
    private val indexer: VaultIndexer,
    private val config: NoteConfigStore,
    private val templateExpander: TemplateExpander = TemplateExpander(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val onVaultMutated: (vaultId: String) -> Unit = {},
) {
    private val noteLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun readNote(vaultId: String, path: VaultPath): String {
        val vault = requireVault(vaultId)
        return storage.readText(vault.rootDir, path)
    }

    suspend fun saveNote(
        vaultId: String,
        path: VaultPath,
        content: String,
    ): VaultFileEntry = withNoteLock(vaultId, path) {
        val vault = requireVault(vaultId)
        val entry = storage.writeText(vault.rootDir, path, content)
        indexer.indexSingleFile(vaultId, vault.rootDir, path, knownContent = content)
        onVaultMutated(vaultId)
        entry
    }

    suspend fun createNote(
        vaultId: String,
        title: String,
        folder: VaultPath? = null,
        templatePath: VaultPath? = null,
        initialContent: String? = null,
    ): VaultPath {
        val vault = requireVault(vaultId)
        val targetFolder = folder ?: resolveDefaultNewNoteFolder(vault.rootDir)
        val safeBaseName = sanitizeFileName(title)
        val path = allocateUniqueMarkdownPath(vault.rootDir, targetFolder, safeBaseName)

        val content = when {
            initialContent != null -> initialContent
            templatePath != null -> {
                val rawTemplate = storage.readTextOrNull(vault.rootDir, templatePath).orEmpty()
                val templatesCfg = config.readTemplatesConfig(vault.rootDir)
                templateExpander.expand(
                    template = rawTemplate,
                    title = path.nameWithoutExtension,
                    defaultDateFormat = templatesCfg.dateFormat,
                    defaultTimeFormat = templatesCfg.timeFormat,
                    now = ZonedDateTime.now(clock),
                )
            }
            else -> ""
        }

        saveNote(vaultId, path, content)
        return path
    }

    suspend fun toggleTask(
        vaultId: String,
        path: VaultPath,
        lineNumber: Int,
        targetStatus: Char? = null,
    ): TaskMutationResult = withNoteLock(vaultId, path) {
        val vault = requireVault(vaultId)
        val current = storage.readText(vault.rootDir, path)
        val result = TaskLineMutator.toggleTask(current, lineNumber, targetStatus)
        if (result is TaskMutationResult.Updated) {
            storage.writeText(vault.rootDir, path, result.content)
            indexer.indexSingleFile(vaultId, vault.rootDir, path, knownContent = result.content)
            onVaultMutated(vaultId)
        }
        result
    }

    suspend fun renameNote(
        vaultId: String,
        from: VaultPath,
        to: VaultPath,
    ) {
        if (from == to) return
        val first = minOf(from, to)
        val second = maxOf(from, to)
        withNoteLock(vaultId, first) {
            withNoteLock(vaultId, second) {
                val vault = requireVault(vaultId)
                storage.rename(vault.rootDir, from, to)
                indexer.reindexPaths(vaultId, vault.rootDir, listOf(from, to))
                onVaultMutated(vaultId)
            }
        }
    }

    suspend fun deleteNote(
        vaultId: String,
        path: VaultPath,
    ): Boolean = withNoteLock(vaultId, path) {
        val vault = requireVault(vaultId)
        val deleted = storage.delete(vault.rootDir, path)
        indexer.reindexPaths(vaultId, vault.rootDir, listOf(path))
        if (deleted) {
            onVaultMutated(vaultId)
        }
        deleted
    }

    suspend fun openOrCreateDailyNote(
        vaultId: String,
        date: LocalDate = LocalDate.now(clock),
    ): DailyNoteResult {
        val vault = requireVault(vaultId)
        val dailyConfig = config.readDailyNotesConfig(vault.rootDir)
        val templatesConfig = config.readTemplatesConfig(vault.rootDir)
        val moment = ZonedDateTime.of(date, LocalTime.now(clock), clock.zone)

        val formattedName = templateExpander.expand(
            template = "{{date:${dailyConfig.format}}}",
            title = "",
            now = moment,
        )
        val dailyPath = buildDailyNotePath(
            folder = dailyConfig.folder,
            formattedName = formattedName,
            fallbackDate = date,
        )

        return withNoteLock(vaultId, dailyPath) {
            if (storage.exists(vault.rootDir, dailyPath)) {
                return@withNoteLock DailyNoteResult(path = dailyPath, created = false)
            }
            val rawTemplate = loadDailyTemplate(vault.rootDir, dailyConfig.template, templatesConfig)
            val expanded = if (rawTemplate.isEmpty()) {
                "# ${dailyPath.nameWithoutExtension}\n\n"
            } else {
                templateExpander.expand(
                    template = rawTemplate,
                    title = dailyPath.nameWithoutExtension,
                    defaultDateFormat = templatesConfig.dateFormat,
                    defaultTimeFormat = templatesConfig.timeFormat,
                    now = moment,
                )
            }
            storage.writeText(vault.rootDir, dailyPath, expanded)
            indexer.indexSingleFile(vaultId, vault.rootDir, dailyPath, knownContent = expanded)
            onVaultMutated(vaultId)
            DailyNoteResult(path = dailyPath, created = true)
        }
    }

    private suspend fun resolveDefaultNewNoteFolder(vaultRoot: File): VaultPath? {
        val appConfig = config.readAppConfig(vaultRoot)
        if (appConfig.newFileLocation != NewFileLocation.FOLDER) return null
        return VaultPath.ofOrNull(appConfig.newFileFolderPath)
    }

    private suspend fun allocateUniqueMarkdownPath(
        vaultRoot: File,
        folder: VaultPath?,
        baseName: String,
    ): VaultPath {
        fun candidate(name: String): VaultPath =
            folder?.resolve("$name.md") ?: VaultPath.of("$name.md")

        val initial = candidate(baseName)
        if (!storage.exists(vaultRoot, initial)) return initial

        var suffix = 1
        while (true) {
            val next = candidate("$baseName $suffix")
            if (!storage.exists(vaultRoot, next)) return next
            suffix++
        }
    }

    private suspend fun loadDailyTemplate(
        vaultRoot: File,
        templateRef: String,
        templatesConfig: TemplatesConfig,
    ): String {
        val trimmed = templateRef.trim()
        if (trimmed.isEmpty()) return ""

        val candidates = buildList {
            VaultPath.ofOrNull(trimmed)?.let(::add)
            if (!trimmed.endsWith(".md", ignoreCase = true)) {
                VaultPath.ofOrNull("$trimmed.md")?.let(::add)
            }
            val folder = templatesConfig.folder.trim().trim('/')
            if (folder.isNotEmpty() && !trimmed.startsWith("$folder/")) {
                VaultPath.ofOrNull("$folder/$trimmed")?.let(::add)
                if (!trimmed.endsWith(".md", ignoreCase = true)) {
                    VaultPath.ofOrNull("$folder/$trimmed.md")?.let(::add)
                }
            }
        }
        for (candidate in candidates) {
            val text = storage.readTextOrNull(vaultRoot, candidate)
            if (text != null) return text
        }
        return ""
    }

    private fun buildDailyNotePath(
        folder: String,
        formattedName: String,
        fallbackDate: LocalDate,
    ): VaultPath {
        val folderSegments = sanitizePathSegments(folder)
        val trimmedName = formattedName.trim().let {
            if (it.endsWith(".md", ignoreCase = true)) it.dropLast(3) else it
        }
        val nameSegments = sanitizePathSegments(trimmedName)
            .ifEmpty { listOf(fallbackDate.toString()) }

        val candidate = (folderSegments + nameSegments).joinToString("/") + ".md"
        return VaultPath.ofOrNull(candidate) ?: VaultPath.of("$fallbackDate.md")
    }

    private fun sanitizePathSegments(raw: String): List<String> =
        raw.replace('\\', '/')
            .split('/')
            .mapNotNull { segment ->
                val trimmed = segment.trim()
                if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") {
                    null
                } else {
                    trimmed
                        .replace(ILLEGAL_FILENAME_CHARS, " ")
                        .replace(MULTI_SPACE, " ")
                        .trim('.', ' ')
                        .takeIf { it.isNotEmpty() }
                }
            }

    private fun sanitizeFileName(rawTitle: String): String {
        val trimmed = rawTitle.trim()
        val withoutExt = if (trimmed.endsWith(".md", ignoreCase = true)) trimmed.dropLast(3) else trimmed
        val cleaned = withoutExt
            .replace(ILLEGAL_FILENAME_CHARS, " ")
            .replace(MULTI_SPACE, " ")
            .trim('.', ' ')
        return cleaned.ifEmpty { "Untitled" }
    }

    private suspend fun requireVault(vaultId: String): VaultConfig =
        vaultRegistry.getVault(vaultId)
            ?: throw IllegalStateException("Vault '$vaultId' is not registered")

    private suspend fun <T> withNoteLock(
        vaultId: String,
        path: VaultPath,
        block: suspend () -> T,
    ): T {
        val mutex = noteLocks.computeIfAbsent("$vaultId:${path.value}") { Mutex() }
        return mutex.withLock { block() }
    }

    private companion object {
        val ILLEGAL_FILENAME_CHARS = Regex("""[/\\:*?"<>|\r\n\t]""")
        val MULTI_SPACE = Regex("""\s+""")
    }
}
