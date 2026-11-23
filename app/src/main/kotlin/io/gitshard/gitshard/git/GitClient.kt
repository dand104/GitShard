package io.gitshard.gitshard.git

import io.gitshard.gitshard.core.VaultPath
import java.io.File

interface GitClient {
    suspend fun init(
        repoDir: File,
        defaultBranch: String = "main",
    )

    suspend fun clone(
        remoteUrl: String,
        targetDir: File,
        credentials: GitCredentials = GitCredentials.None,
        branch: String? = null,
    )

    suspend fun isGitRepository(dir: File): Boolean

    suspend fun status(repoDir: File): GitStatusSnapshot

    suspend fun commitAllIfDirty(
        repoDir: File,
        message: String,
        author: GitAuthor = GitAuthor.DEFAULT,
    ): CommitResult

    suspend fun pull(
        repoDir: File,
        credentials: GitCredentials = GitCredentials.None,
        author: GitAuthor = GitAuthor.DEFAULT,
    ): PullResult

    suspend fun push(
        repoDir: File,
        credentials: GitCredentials = GitCredentials.None,
    ): PushResult

    suspend fun diffPaths(
        repoDir: File,
        fromCommit: String?,
        toCommit: String,
    ): Set<VaultPath>

    suspend fun log(
        repoDir: File,
        limit: Int = 50,
    ): List<GitCommit>

    suspend fun markResolved(
        repoDir: File,
        path: VaultPath,
        deleted: Boolean = false,
    )

    suspend fun abortMerge(repoDir: File)
}
