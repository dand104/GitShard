package io.gitshard.gitshard.git.jgit

import io.gitshard.gitshard.core.AppDispatchers
import io.gitshard.gitshard.core.VaultPath
import io.gitshard.gitshard.git.CommitResult
import io.gitshard.gitshard.git.FileChange
import io.gitshard.gitshard.git.FileChangeKind
import io.gitshard.gitshard.git.GitAuthor
import io.gitshard.gitshard.git.GitClient
import io.gitshard.gitshard.git.GitCommit
import io.gitshard.gitshard.git.GitCredentials
import io.gitshard.gitshard.git.GitRepoState
import io.gitshard.gitshard.git.GitStatusSnapshot
import io.gitshard.gitshard.git.PullResult
import io.gitshard.gitshard.git.PushResult
import io.gitshard.gitshard.git.jgit.JGitAuth.applyTransportConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.api.errors.InvalidConfigurationException
import org.eclipse.jgit.api.errors.RefNotAdvertisedException
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCacheCheckout
import org.eclipse.jgit.errors.CheckoutConflictException as CoreCheckoutConflictException
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryCache
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class JGitClient(
    private val dispatchers: AppDispatchers = AppDispatchers(),
) : GitClient {

    private val repoLocks = ConcurrentHashMap<String, Mutex>()

    init {
        JGitConfig.installGlobalDefaults()
    }

    override suspend fun init(repoDir: File, defaultBranch: String) =
        withRepoLock(repoDir) {
            repoDir.mkdirs()
            Git.init()
                .setDirectory(repoDir)
                .setInitialBranch(defaultBranch)
                .call()
                .use { git ->
                    JGitConfig.applyRepoDefaults(git.repository)
                }
        }

    override suspend fun clone(
        remoteUrl: String,
        targetDir: File,
        credentials: GitCredentials,
        branch: String?,
    ) = withRepoLock(targetDir) {
        val cmd = Git.cloneRepository()
            .setURI(remoteUrl)
            .setDirectory(targetDir)
            .applyTransportConfig(credentials)
        if (!branch.isNullOrBlank()) {
            cmd.setBranch(branch)
        }
        cmd.call().use { git ->
            val repo = git.repository
            if (repo.resolve(Constants.HEAD) == null) {
                val targetBranch = branch?.takeIf { it.isNotBlank() } ?: DEFAULT_BRANCH
                val fullBranchRef = Constants.R_HEADS + targetBranch
                if (repo.fullBranch != fullBranchRef) {
                    repo.updateRef(Constants.HEAD).link(fullBranchRef)
                }
                repo.config.apply {
                    setString(
                        ConfigConstants.CONFIG_BRANCH_SECTION,
                        targetBranch,
                        ConfigConstants.CONFIG_KEY_REMOTE,
                        Constants.DEFAULT_REMOTE_NAME,
                    )
                    setString(
                        ConfigConstants.CONFIG_BRANCH_SECTION,
                        targetBranch,
                        ConfigConstants.CONFIG_KEY_MERGE,
                        fullBranchRef,
                    )
                    save()
                }
            }
            JGitConfig.applyRepoDefaults(repo)
        }
    }

    override suspend fun isGitRepository(dir: File): Boolean =
        withContext(dispatchers.io) {
            val gitDir = File(dir, Constants.DOT_GIT)
            gitDir.exists() && RepositoryCache.FileKey.isGitRepository(gitDir, FS.DETECTED)
        }

    override suspend fun status(repoDir: File): GitStatusSnapshot =
        withRepoLock(repoDir) {
            openGit(repoDir).use { git ->
                val status = git.status().call()
                val changes = ArrayList<FileChange>()
                val conflicting = LinkedHashSet<VaultPath>()

                status.conflicting.forEach { raw ->
                    VaultPath.ofOrNull(raw)?.let { path ->
                        conflicting.add(path)
                        changes.add(FileChange(path, FileChangeKind.CONFLICTING, isStaged = false))
                    }
                }
                status.added.forEach { raw ->
                    VaultPath.ofOrNull(raw)?.takeIf { it !in conflicting }?.let {
                        changes.add(FileChange(it, FileChangeKind.ADDED, isStaged = true))
                    }
                }
                status.changed.forEach { raw ->
                    VaultPath.ofOrNull(raw)?.takeIf { it !in conflicting }?.let {
                        changes.add(FileChange(it, FileChangeKind.MODIFIED, isStaged = true))
                    }
                }
                status.removed.forEach { raw ->
                    VaultPath.ofOrNull(raw)?.takeIf { it !in conflicting }?.let {
                        changes.add(FileChange(it, FileChangeKind.DELETED, isStaged = true))
                    }
                }
                status.modified.forEach { raw ->
                    VaultPath.ofOrNull(raw)?.takeIf { it !in conflicting }?.let {
                        changes.add(FileChange(it, FileChangeKind.MODIFIED, isStaged = false))
                    }
                }
                status.missing.forEach { raw ->
                    VaultPath.ofOrNull(raw)?.takeIf { it !in conflicting }?.let {
                        changes.add(FileChange(it, FileChangeKind.DELETED, isStaged = false))
                    }
                }
                status.untracked.forEach { raw ->
                    VaultPath.ofOrNull(raw)?.takeIf { it !in conflicting }?.let {
                        changes.add(FileChange(it, FileChangeKind.UNTRACKED, isStaged = false))
                    }
                }

                GitStatusSnapshot(
                    branch = git.repository.branch,
                    repoState = git.repository.repositoryState.toGitRepoState(),
                    changes = changes.sortedBy { it.path },
                    conflictingPaths = conflicting,
                )
            }
        }

    override suspend fun commitAllIfDirty(
        repoDir: File,
        message: String,
        author: GitAuthor,
    ): CommitResult = withRepoLock(repoDir) {
        openGit(repoDir).use { git ->
            val beforeStatus = git.status().call()
            if (beforeStatus.conflicting.isNotEmpty()) {
                val paths = beforeStatus.conflicting.mapNotNullTo(LinkedHashSet(), VaultPath::ofOrNull)
                return@withRepoLock CommitResult.HasConflicts(paths)
            }

            val isMergeCompletion = git.repository.repositoryState == RepositoryState.MERGING_RESOLVED
            val changedCount = LinkedHashSet<String>().apply {
                addAll(beforeStatus.added)
                addAll(beforeStatus.changed)
                addAll(beforeStatus.removed)
                addAll(beforeStatus.modified)
                addAll(beforeStatus.missing)
                addAll(beforeStatus.untracked)
            }.size
            if (!isMergeCompletion && changedCount == 0) {
                return@withRepoLock CommitResult.NothingToCommit
            }

            if (beforeStatus.untracked.isNotEmpty() || beforeStatus.modified.isNotEmpty()) {
                git.add().addFilepattern(".").call()
            }
            if (beforeStatus.missing.isNotEmpty()) {
                git.add().setUpdate(true).addFilepattern(".").call()
            }

            val ident = PersonIdent(author.name, author.email)
            val commit = git.commit()
                .setMessage(message)
                .setAuthor(ident)
                .setCommitter(ident)
                .setSign(false)
                .call()

            CommitResult.Committed(
                commitId = commit.id.name,
                changedCount = changedCount,
            )
        }
    }

    override suspend fun pull(
        repoDir: File,
        credentials: GitCredentials,
        author: GitAuthor,
    ): PullResult = withRepoLock(repoDir) {
        openGit(repoDir).use { git ->
            val repo = git.repository
            when (repo.repositoryState) {
                RepositoryState.MERGING -> {
                    val conflictPaths = git.status().call().conflicting
                        .mapNotNullTo(LinkedHashSet(), VaultPath::ofOrNull)
                    return@withRepoLock PullResult.Conflicts(conflictPaths)
                }
                RepositoryState.MERGING_RESOLVED -> {
                    return@withRepoLock PullResult.Failed(
                        message = "Merge conflicts are resolved; commit before pulling",
                    )
                }
                else -> Unit
            }

            val fullBranch = repo.fullBranch
                ?: return@withRepoLock PullResult.Failed("Repository has no HEAD")
            if (!fullBranch.startsWith(Constants.R_HEADS)) {
                return@withRepoLock PullResult.Failed("Cannot pull on detached HEAD")
            }

            val branchName = Repository.shortenRefName(fullBranch)
            val branchConfig = BranchConfig(repo.config, branchName)
            val remoteName = resolveRemoteName(git, branchName)
                ?: return@withRepoLock PullResult.NoRemote

            try {
                git.fetch()
                    .setRemote(remoteName)
                    .applyTransportConfig(credentials)
                    .call()
            } catch (_: RefNotAdvertisedException) {
                return@withRepoLock PullResult.UpToDate
            } catch (_: InvalidConfigurationException) {
                return@withRepoLock PullResult.NoRemote
            }

            val trackingRefName = branchConfig.trackingBranch?.takeIf { it.isNotBlank() }
                ?: "${Constants.R_REMOTES}$remoteName/$branchName"
            val trackingRef = repo.exactRef(trackingRefName)
                ?: return@withRepoLock PullResult.UpToDate

            val oldHeadId = repo.resolve(Constants.HEAD)
            if (oldHeadId == null) {
                return@withRepoLock fastForwardUnbornBranch(
                    repo = repo,
                    fullBranch = fullBranch,
                    targetId = trackingRef.objectId ?: return@withRepoLock PullResult.UpToDate,
                )
            }

            val mergeResult = try {
                git.merge()
                    .include(trackingRef)
                    .setFastForward(MergeCommand.FastForwardMode.FF)
                    .setCommit(false)
                    .call()
            } catch (e: CheckoutConflictException) {
                val paths = e.conflictingPaths.mapNotNullTo(LinkedHashSet(), VaultPath::ofOrNull)
                return@withRepoLock PullResult.Failed(
                    message = e.message ?: "Local changes would be overwritten by pull",
                    conflictingPaths = paths,
                )
            }

            when (mergeResult.mergeStatus) {
                MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> PullResult.UpToDate

                MergeResult.MergeStatus.FAST_FORWARD,
                MergeResult.MergeStatus.FAST_FORWARD_SQUASHED,
                MergeResult.MergeStatus.MERGED,
                -> {
                    val newHeadId = repo.resolve(Constants.HEAD)
                    PullResult.Merged(
                        changedPaths = diffPathsInternal(repo, oldHeadId, newHeadId),
                        fromCommit = oldHeadId?.name,
                        toCommit = newHeadId?.name,
                    )
                }

                MergeResult.MergeStatus.MERGED_NOT_COMMITTED -> {
                    val ident = PersonIdent(author.name, author.email)
                    val mergeMessage = repo.readMergeCommitMsg()
                        ?.takeIf { it.isNotBlank() }
                        ?: "Merge '$trackingRefName' into $branchName"
                    val commit = git.commit()
                        .setMessage(mergeMessage)
                        .setAuthor(ident)
                        .setCommitter(ident)
                        .setSign(false)
                        .call()
                    PullResult.Merged(
                        changedPaths = diffPathsInternal(repo, oldHeadId, commit.id),
                        fromCommit = oldHeadId?.name,
                        toCommit = commit.id.name,
                    )
                }

                MergeResult.MergeStatus.CONFLICTING -> {
                    val conflictPaths = LinkedHashSet<VaultPath>().apply {
                        mergeResult.conflicts?.keys?.mapNotNullTo(this, VaultPath::ofOrNull)
                        git.status().call().conflicting.mapNotNullTo(this, VaultPath::ofOrNull)
                    }
                    PullResult.Conflicts(conflictPaths)
                }

                else -> {
                    val checkoutConflicts = mergeResult.checkoutConflicts
                        ?.mapNotNullTo(LinkedHashSet(), VaultPath::ofOrNull)
                        .orEmpty()
                    PullResult.Failed(
                        message = mergeResult.mergeStatus?.toString() ?: "Pull failed",
                        conflictingPaths = checkoutConflicts,
                    )
                }
            }
        }
    }

    override suspend fun push(
        repoDir: File,
        credentials: GitCredentials,
    ): PushResult = withRepoLock(repoDir) {
        openGit(repoDir).use { git ->
            val repo = git.repository
            val fullBranch = repo.fullBranch?.takeIf { it.startsWith(Constants.R_HEADS) }
            val branchName = fullBranch?.let(Repository::shortenRefName)
            val remoteName = resolveRemoteName(git, branchName)
                ?: return@withRepoLock PushResult.NoRemote
            if (repo.resolve(Constants.HEAD) == null) {
                return@withRepoLock PushResult.UpToDate
            }

            val pushCmd = git.push()
                .setRemote(remoteName)
                .applyTransportConfig(credentials)

            if (fullBranch != null && branchName != null &&
                BranchConfig(repo.config, branchName).remote.isNullOrBlank()
            ) {
                pushCmd.setRefSpecs(RefSpec("$fullBranch:$fullBranch"))
            }

            val results = pushCmd.call()

            val updates = results.flatMap { it.remoteUpdates }
            if (updates.isEmpty()) return@withRepoLock PushResult.UpToDate

            var anyPushed = false
            for (update in updates) {
                when (update.status) {
                    RemoteRefUpdate.Status.OK -> anyPushed = true
                    RemoteRefUpdate.Status.UP_TO_DATE -> Unit
                    RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD -> {
                        return@withRepoLock PushResult.RejectedNonFastForward(
                            update.message ?: "Remote contains work that you do not have locally",
                        )
                    }
                    else -> {
                        return@withRepoLock PushResult.Rejected(
                            update.message ?: "Push rejected (${update.status})",
                        )
                    }
                }
            }

            if (anyPushed) PushResult.Pushed else PushResult.UpToDate
        }
    }

    override suspend fun diffPaths(
        repoDir: File,
        fromCommit: String?,
        toCommit: String,
    ): Set<VaultPath> = withRepoLock(repoDir) {
        openGit(repoDir).use { git ->
            val repo = git.repository
            val fromId = fromCommit?.let(repo::resolve)
            val toId = repo.resolve(toCommit) ?: return@withRepoLock emptySet()
            diffPathsInternal(repo, fromId, toId)
        }
    }

    override suspend fun log(
        repoDir: File,
        limit: Int,
    ): List<GitCommit> = withRepoLock(repoDir) {
        openGit(repoDir).use { git ->
            if (git.repository.resolve(Constants.HEAD) == null) {
                return@withRepoLock emptyList()
            }
            git.log()
                .setMaxCount(limit.coerceAtLeast(1))
                .call()
                .map { commit ->
                    GitCommit(
                        id = commit.id.name,
                        shortId = commit.id.abbreviate(7).name(),
                        shortMessage = commit.shortMessage,
                        fullMessage = commit.fullMessage,
                        authorName = commit.authorIdent?.name.orEmpty(),
                        authorEmail = commit.authorIdent?.emailAddress.orEmpty(),
                        timestampEpochSeconds = commit.commitTime.toLong(),
                    )
                }
        }
    }

    override suspend fun markResolved(
        repoDir: File,
        path: VaultPath,
        deleted: Boolean,
    ) = withRepoLock(repoDir) {
        openGit(repoDir).use { git ->
            if (deleted) {
                git.rm().addFilepattern(path.value).call()
            } else {
                git.add().addFilepattern(path.value).call()
            }
            Unit
        }
    }

    override suspend fun abortMerge(repoDir: File) = withRepoLock(repoDir) {
        openGit(repoDir).use { git ->
            val repo = git.repository
            git.reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef(Constants.HEAD)
                .call()
            if (repo.repositoryState != RepositoryState.SAFE) {
                repo.writeMergeHeads(null)
                repo.writeMergeCommitMsg(null)
            }
        }
    }

    private suspend fun <T> withRepoLock(repoDir: File, block: () -> T): T =
        withContext(dispatchers.io) {
            val lockKey = repoDir.canonicalPath
            val mutex = repoLocks.computeIfAbsent(lockKey) { Mutex() }
            mutex.withLock(action = block)
        }

    private fun openGit(repoDir: File): Git = Git.open(repoDir)

    private fun resolveRemoteName(git: Git, branchName: String?): String? {
        if (!branchName.isNullOrBlank()) {
            val trackingRemote = BranchConfig(git.repository.config, branchName).remote
            if (!trackingRemote.isNullOrBlank()) return trackingRemote
        }
        return git.remoteList().call().firstOrNull()?.name
    }

    private fun fastForwardUnbornBranch(
        repo: Repository,
        fullBranch: String,
        targetId: ObjectId,
    ): PullResult {
        val targetTreeId = RevWalk(repo).use { revWalk ->
            revWalk.parseCommit(targetId).tree.id
        }
        val dirCache = repo.lockDirCache()
        val checkout = DirCacheCheckout(repo, dirCache, targetTreeId).apply {
            setFailOnConflict(true)
        }
        try {
            checkout.checkout()
        } catch (e: CoreCheckoutConflictException) {
            val paths = checkout.conflicts.mapNotNullTo(LinkedHashSet(), VaultPath::ofOrNull)
            return PullResult.Failed(
                message = e.message ?: "Local files would be overwritten by pull",
                conflictingPaths = paths,
            )
        } finally {
            dirCache.unlock()
        }

        val result = repo.updateRef(fullBranch).apply {
            setNewObjectId(targetId)
            setRefLogMessage("pull: initial fast-forward", false)
        }.update()
        if (result != RefUpdate.Result.NEW &&
            result != RefUpdate.Result.FAST_FORWARD &&
            result != RefUpdate.Result.FORCED &&
            result != RefUpdate.Result.NO_CHANGE
        ) {
            return PullResult.Failed("Failed to initialize branch $fullBranch: $result")
        }

        return PullResult.Merged(
            changedPaths = diffPathsInternal(repo, fromId = null, toId = targetId),
            fromCommit = null,
            toCommit = targetId.name,
        )
    }

    private fun diffPathsInternal(
        repo: Repository,
        fromId: ObjectId?,
        toId: ObjectId?,
    ): Set<VaultPath> {
        if (fromId == toId) return emptySet()
        val oldTree = prepareTreeParser(repo, fromId)
        val newTree = prepareTreeParser(repo, toId)
        val changed = LinkedHashSet<VaultPath>()

        DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
            formatter.setRepository(repo)
            formatter.isDetectRenames = true
            for (entry in formatter.scan(oldTree, newTree)) {
                if (entry.oldPath != DiffEntry.DEV_NULL) {
                    VaultPath.ofOrNull(entry.oldPath)?.let(changed::add)
                }
                if (entry.newPath != DiffEntry.DEV_NULL) {
                    VaultPath.ofOrNull(entry.newPath)?.let(changed::add)
                }
            }
        }
        return changed
    }

    private fun prepareTreeParser(repo: Repository, commitId: ObjectId?): AbstractTreeIterator {
        if (commitId == null) return EmptyTreeIterator()
        RevWalk(repo).use { revWalk ->
            val commit = revWalk.parseCommit(commitId)
            val treeId = commit.tree.id
            repo.newObjectReader().use { reader ->
                return CanonicalTreeParser(null, reader, treeId)
            }
        }
    }

    private fun RepositoryState.toGitRepoState(): GitRepoState = when (this) {
        RepositoryState.SAFE -> GitRepoState.CLEAN
        RepositoryState.MERGING -> GitRepoState.MERGING
        RepositoryState.MERGING_RESOLVED -> GitRepoState.MERGING_RESOLVED
        RepositoryState.REBASING,
        RepositoryState.REBASING_REBASING,
        RepositoryState.REBASING_MERGE,
        RepositoryState.REBASING_INTERACTIVE,
            -> GitRepoState.REBASING
        else -> GitRepoState.OTHER
    }

    private companion object {
        const val DEFAULT_BRANCH = "main"
    }
}
