package io.gitshard.gitshard.data.sync

import io.gitshard.gitshard.core.AppDispatchers
import io.gitshard.gitshard.core.VaultPath
import io.gitshard.gitshard.data.VaultIndexer
import io.gitshard.gitshard.data.prefs.AppPreferences
import io.gitshard.gitshard.data.prefs.CredentialsReadResult
import io.gitshard.gitshard.data.prefs.CredentialsStore
import io.gitshard.gitshard.data.prefs.VaultConfig
import io.gitshard.gitshard.data.prefs.VaultRegistryStore
import io.gitshard.gitshard.git.CommitResult
import io.gitshard.gitshard.git.GitClient
import io.gitshard.gitshard.git.GitCredentials
import io.gitshard.gitshard.git.GitException
import io.gitshard.gitshard.git.PullResult
import io.gitshard.gitshard.git.PushResult
import io.gitshard.gitshard.git.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

enum class SyncPhase {
    COMMITTING,
    PULLING,
    INDEXING,
    PUSHING,
}

sealed interface VaultSyncState {
    data object Idle : VaultSyncState

    data class Syncing(val phase: SyncPhase) : VaultSyncState

    data class Synced(
        val atEpochMillis: Long,
        val result: SyncResult,
    ) : VaultSyncState

    data class Conflicts(
        val atEpochMillis: Long,
        val paths: Set<VaultPath>,
    ) : VaultSyncState

    data class Failed(
        val atEpochMillis: Long,
        val message: String,
    ) : VaultSyncState
}

class VaultSyncCoordinator internal constructor(
    private val vaultRegistry: VaultRegistryStore,
    private val credentialsStore: CredentialsStore,
    private val appPreferences: AppPreferences,
    private val gitClient: GitClient,
    private val reindexPaths: suspend (vaultId: String, vaultRoot: File, paths: Collection<VaultPath>) -> Unit,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers = AppDispatchers(),
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    constructor(
        vaultRegistry: VaultRegistryStore,
        credentialsStore: CredentialsStore,
        appPreferences: AppPreferences,
        gitClient: GitClient,
        indexer: VaultIndexer,
        scope: CoroutineScope,
        dispatchers: AppDispatchers = AppDispatchers(),
        clock: Clock = Clock.systemDefaultZone(),
    ) : this(
        vaultRegistry = vaultRegistry,
        credentialsStore = credentialsStore,
        appPreferences = appPreferences,
        gitClient = gitClient,
        reindexPaths = { vaultId, vaultRoot, paths ->
            indexer.reindexPaths(vaultId, vaultRoot, paths)
        },
        scope = scope,
        dispatchers = dispatchers,
        clock = clock,
    )

    private val vaultLocks = ConcurrentHashMap<String, Mutex>()
    private val debounceJobs = ConcurrentHashMap<String, Job>()
    private val _syncStates = MutableStateFlow(emptyMap<String, VaultSyncState>())

    val syncStates = _syncStates.asStateFlow()

    fun observeState(vaultId: String): Flow<VaultSyncState> =
        syncStates.map { it[vaultId] ?: VaultSyncState.Idle }.distinctUntilChanged()

    fun scheduleDebouncedSync(vaultId: String) {
        val newJob = scope.launch(dispatchers.io, start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            val settings = appPreferences.current()
            if (!settings.autoSyncEnabled) return@launch
            delay(settings.syncDebounceMillis.milliseconds)
            if (self != null) {
                debounceJobs.remove(vaultId, self)
            }
            if (!appPreferences.current().autoSyncEnabled) return@launch
            syncInternal(vaultId = vaultId, commitMessage = null, cancelPendingDebounce = false)
        }
        newJob.invokeOnCompletion {
            debounceJobs.remove(vaultId, newJob)
        }
        debounceJobs.put(vaultId, newJob)?.cancel()
        newJob.start()
    }

    fun clearVaultState(vaultId: String) {
        cancelDebouncedSync(vaultId)
        _syncStates.update { it - vaultId }
    }

    suspend fun sync(
        vaultId: String,
        commitMessage: String? = null,
    ): SyncResult = syncInternal(
        vaultId = vaultId,
        commitMessage = commitMessage,
        cancelPendingDebounce = true,
    )

    suspend fun syncAll(): Map<String, SyncResult> {
        val vaults = vaultRegistry.getAllVaults()
        val results = LinkedHashMap<String, SyncResult>(vaults.size)
        for (vault in vaults) {
            withVaultLock(vault.id) {
                if (vault.rootDir.isDirectory && gitClient.isGitRepository(vault.rootDir)) {
                    cancelDebouncedSync(vault.id)
                    results[vault.id] = runSyncLocked(vault = vault, commitMessage = null)
                }
            }
        }
        return results
    }

    private suspend fun syncInternal(
        vaultId: String,
        commitMessage: String?,
        cancelPendingDebounce: Boolean,
    ): SyncResult = withVaultLock(vaultId) {
        if (cancelPendingDebounce) {
            cancelDebouncedSync(vaultId)
        }
        val vault = vaultRegistry.getVault(vaultId)
            ?: return@withVaultLock SyncResult.Error("Vault '$vaultId' is not registered")

        if (!vault.rootDir.isDirectory || !gitClient.isGitRepository(vault.rootDir)) {
            val message = "Vault '${vault.name}' is not a Git repository"
            updateState(vaultId, VaultSyncState.Failed(clock.millis(), message))
            return@withVaultLock SyncResult.Error(message)
        }

        runSyncLocked(vault = vault, commitMessage = commitMessage)
    }

    private suspend fun runSyncLocked(
        vault: VaultConfig,
        commitMessage: String?,
    ): SyncResult {
        val vaultId = vault.id
        val previousState = _syncStates.value[vaultId] ?: VaultSyncState.Idle
        var terminalStateSet = false

        fun finish(state: VaultSyncState, result: SyncResult): SyncResult {
            updateState(vaultId, state)
            terminalStateSet = true
            return result
        }

        return try {
            val credentials = when (val creds = credentialsStore.read(vaultId)) {
                is CredentialsReadResult.Available -> creds.credentials
                CredentialsReadResult.Empty -> GitCredentials.None
                is CredentialsReadResult.DecryptionFailed -> {
                    val message = "Stored Git credentials could not be decrypted. Re-enter them in Settings."
                    return finish(
                        state = VaultSyncState.Failed(clock.millis(), message),
                        result = SyncResult.Error(message, creds.cause, isRetryable = false),
                    )
                }
            }

            updateState(vaultId, VaultSyncState.Syncing(SyncPhase.COMMITTING))
            val effectiveMessage = commitMessage?.trim()?.takeIf { it.isNotEmpty() } ?: defaultCommitMessage()
            val committedId = when (val commit = gitClient.commitAllIfDirty(vault.rootDir, effectiveMessage, vault.author)) {
                is CommitResult.Committed -> commit.commitId
                CommitResult.NothingToCommit -> null
                is CommitResult.HasConflicts -> {
                    return finish(
                        state = VaultSyncState.Conflicts(clock.millis(), commit.paths),
                        result = SyncResult.Conflicts(commit.paths),
                    )
                }
            }

            val pulledPaths = LinkedHashSet<VaultPath>()
            var lastNonFastForward: PushResult.RejectedNonFastForward? = null
            repeat(MAX_PUSH_ATTEMPTS) {
                updateState(vaultId, VaultSyncState.Syncing(SyncPhase.PULLING))
                when (val pull = gitClient.pull(vault.rootDir, credentials, vault.author)) {
                    PullResult.NoRemote -> {
                        val result = syncedOrUpToDate(committedId, pulledPaths, pushed = false)
                        return finish(VaultSyncState.Synced(clock.millis(), result), result)
                    }

                    PullResult.UpToDate -> Unit

                    is PullResult.Merged -> {
                        if (pull.changedPaths.isNotEmpty()) {
                            pulledPaths.addAll(pull.changedPaths)
                            updateState(vaultId, VaultSyncState.Syncing(SyncPhase.INDEXING))
                            reindexPaths(vaultId, vault.rootDir, pull.changedPaths)
                        }
                    }

                    is PullResult.Conflicts -> {
                        val affectedPaths = collectChangedPaths(vault.rootDir, pull.paths)
                        if (affectedPaths.isNotEmpty()) {
                            updateState(vaultId, VaultSyncState.Syncing(SyncPhase.INDEXING))
                            reindexPaths(vaultId, vault.rootDir, affectedPaths)
                        }
                        return finish(
                            state = VaultSyncState.Conflicts(clock.millis(), pull.paths),
                            result = SyncResult.Conflicts(pull.paths),
                        )
                    }

                    is PullResult.Failed -> {
                        return finish(
                            state = VaultSyncState.Failed(clock.millis(), pull.message),
                            result = SyncResult.Error(pull.message, isRetryable = pull.isRetryable),
                        )
                    }
                }

                updateState(vaultId, VaultSyncState.Syncing(SyncPhase.PUSHING))
                when (val push = gitClient.push(vault.rootDir, credentials)) {
                    PushResult.Pushed -> {
                        val result = SyncResult.Synced(
                            commitId = committedId,
                            pulledPaths = pulledPaths,
                            pushed = true,
                        )
                        return finish(VaultSyncState.Synced(clock.millis(), result), result)
                    }

                    PushResult.UpToDate,
                    PushResult.NoRemote,
                        -> {
                        val result = syncedOrUpToDate(committedId, pulledPaths, pushed = false)
                        return finish(VaultSyncState.Synced(clock.millis(), result), result)
                    }

                    is PushResult.RejectedNonFastForward -> {
                        lastNonFastForward = push
                    }

                    is PushResult.Rejected -> {
                        return finish(
                            state = VaultSyncState.Failed(clock.millis(), push.message),
                            result = SyncResult.Error(push.message, isRetryable = false),
                        )
                    }
                }
            }

            val message = lastNonFastForward?.message
                ?: "Remote contains work that you do not have locally"
            finish(
                state = VaultSyncState.Failed(clock.millis(), message),
                result = SyncResult.Error(message, isRetryable = true),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val retryable = when (e) {
                is GitException -> e.kind.isRetryable
                is IOException -> true
                else -> false
            }
            val message = e.readableMessage()
            finish(
                state = VaultSyncState.Failed(clock.millis(), message),
                result = SyncResult.Error(message, e, isRetryable = retryable),
            )
        } finally {
            if (!terminalStateSet && _syncStates.value[vaultId] is VaultSyncState.Syncing) {
                updateState(vaultId, previousState)
            }
        }
    }

    private fun cancelDebouncedSync(vaultId: String) {
        debounceJobs.remove(vaultId)?.cancel()
    }

    private suspend fun collectChangedPaths(repoDir: File, basePaths: Set<VaultPath>): Set<VaultPath> {
        val status = gitClient.status(repoDir)
        return LinkedHashSet<VaultPath>(basePaths.size + status.changes.size).apply {
            addAll(basePaths)
            addAll(status.conflictingPaths)
            status.changes.mapTo(this) { it.path }
        }
    }

    private fun syncedOrUpToDate(
        commitId: String?,
        pulledPaths: Set<VaultPath>,
        pushed: Boolean,
    ): SyncResult = if (commitId != null || pulledPaths.isNotEmpty() || pushed) {
        SyncResult.Synced(commitId = commitId, pulledPaths = pulledPaths, pushed = pushed)
    } else {
        SyncResult.UpToDate
    }

    private fun defaultCommitMessage(): String =
        "GitShard sync ${COMMIT_TIME_FORMAT.format(ZonedDateTime.now(clock))}"

    private fun Throwable.readableMessage(): String =
        generateSequence(this) { it.cause }.firstNotNullOfOrNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
            ?: javaClass.simpleName

    private fun updateState(vaultId: String, state: VaultSyncState) {
        _syncStates.update { it + (vaultId to state) }
    }

    private suspend fun <T> withVaultLock(vaultId: String, block: suspend () -> T): T =
        withContext(dispatchers.io) {
            val mutex = vaultLocks.computeIfAbsent(vaultId) { Mutex() }
            mutex.withLock { block() }
        }

    private companion object {
        const val MAX_PUSH_ATTEMPTS = 2
        val COMMIT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
    }
}
