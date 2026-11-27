package io.gitshard.gitshard.git

import io.gitshard.gitshard.core.VaultPath

enum class GitErrorKind(val isRetryable: Boolean) {
    NETWORK(isRetryable = true),
    LOCK(isRetryable = true),
    AUTH(isRetryable = false),
    CONFIG(isRetryable = false),
    UNKNOWN(isRetryable = false),
}

class GitException(
    val kind: GitErrorKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class GitAuthor(
    val name: String,
    val email: String,
) {
    companion object {
        val DEFAULT = GitAuthor(name = "GitShard", email = "gitshard@local")
    }
}

sealed interface GitCredentials {
    data object None : GitCredentials

    data class HttpsToken(
        val token: String,
        val username: String = "git",
    ) : GitCredentials

    data class HttpsPassword(
        val username: String,
        val password: String,
    ) : GitCredentials

    data class SshKey(
        val privateKeyPem: String,
        val passphrase: String? = null,
    ) : GitCredentials
}

enum class FileChangeKind {
    ADDED,
    MODIFIED,
    DELETED,
    UNTRACKED,
    CONFLICTING,
}

data class FileChange(
    val path: VaultPath,
    val kind: FileChangeKind,
    val isStaged: Boolean,
)

enum class GitRepoState {
    CLEAN,
    MERGING,
    MERGING_RESOLVED,
    REBASING,
    OTHER,
}

data class GitStatusSnapshot(
    val branch: String?,
    val repoState: GitRepoState,
    val changes: List<FileChange>,
    val conflictingPaths: Set<VaultPath>,
) {
    val isClean: Boolean
        get() = changes.isEmpty() && conflictingPaths.isEmpty()

    val hasConflicts: Boolean
        get() = conflictingPaths.isNotEmpty()
}

data class GitCommit(
    val id: String,
    val shortId: String,
    val shortMessage: String,
    val fullMessage: String,
    val authorName: String,
    val authorEmail: String,
    val timestampEpochSeconds: Long,
)

enum class ConflictKind {
    CONTENT,
    DELETED_OR_MISSING,
    BINARY_OR_UNPARSEABLE,
}

data class ConflictFile(
    val path: VaultPath,
    val kind: ConflictKind,
    val document: ParsedConflictDocument? = null,
)

sealed interface CommitResult {
    data class Committed(
        val commitId: String,
        val changedCount: Int,
    ) : CommitResult

    data object NothingToCommit : CommitResult

    data class HasConflicts(
        val paths: Set<VaultPath>,
    ) : CommitResult
}

sealed interface PullResult {
    data object UpToDate : PullResult

    data object NoRemote : PullResult

    data class Merged(
        val changedPaths: Set<VaultPath>,
        val fromCommit: String?,
        val toCommit: String?,
    ) : PullResult

    data class Conflicts(
        val paths: Set<VaultPath>,
    ) : PullResult

    data class Failed(
        val message: String,
        val conflictingPaths: Set<VaultPath> = emptySet(),
        val isRetryable: Boolean = false,
    ) : PullResult
}

sealed interface PushResult {
    data object Pushed : PushResult

    data object UpToDate : PushResult

    data object NoRemote : PushResult

    data class RejectedNonFastForward(
        val message: String,
    ) : PushResult

    data class Rejected(
        val message: String,
    ) : PushResult
}

sealed interface SyncResult {
    data object UpToDate : SyncResult

    data class Synced(
        val commitId: String?,
        val pulledPaths: Set<VaultPath>,
        val pushed: Boolean,
    ) : SyncResult

    data class Conflicts(
        val paths: Set<VaultPath>,
    ) : SyncResult

    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val isRetryable: Boolean = false,
    ) : SyncResult
}
