package io.gitshard.gitshard.data.sync

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.gitshard.gitshard.core.AppDispatchers
import io.gitshard.gitshard.core.AtomicFileWriter
import io.gitshard.gitshard.core.VaultPath
import io.gitshard.gitshard.data.prefs.AppPreferences
import io.gitshard.gitshard.data.prefs.CredentialsStore
import io.gitshard.gitshard.data.prefs.VaultConfig
import io.gitshard.gitshard.data.prefs.VaultRegistryState
import io.gitshard.gitshard.data.prefs.VaultRegistryStore
import io.gitshard.gitshard.git.CommitResult
import io.gitshard.gitshard.git.GitAuthor
import io.gitshard.gitshard.git.GitClient
import io.gitshard.gitshard.git.GitCommit
import io.gitshard.gitshard.git.GitCredentials
import io.gitshard.gitshard.git.GitErrorKind
import io.gitshard.gitshard.git.GitException
import io.gitshard.gitshard.git.GitRepoState
import io.gitshard.gitshard.git.GitStatusSnapshot
import io.gitshard.gitshard.git.PullResult
import io.gitshard.gitshard.git.PushResult
import io.gitshard.gitshard.git.SyncResult
import io.gitshard.gitshard.git.jgit.JGitClient
import io.gitshard.gitshard.testutil.InMemoryDataStore
import io.gitshard.gitshard.testutil.IsolatedJGitRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class VaultSyncCoordinatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val isolatedJGit = IsolatedJGitRule()

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-11-27T21:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `commits pulls reindexes changed paths and pushes end to end with JGit`() = runTest {
        val dispatchers = testDispatchers()
        val gitClient = JGitClient(dispatchers)
        val (desktopDir, mobileDir, remoteUri) = createPairedJGitRepos(gitClient)

        AtomicFileWriter.writeText(File(desktopDir, "RemoteNote.md"), "# From Desktop\n")
        gitClient.commitAllIfDirty(desktopDir, "Add RemoteNote")
        assertEquals(PushResult.Pushed, gitClient.push(desktopDir))

        AtomicFileWriter.writeText(File(mobileDir, "MobileNote.md"), "# From Mobile\n")

        val reindexed = ArrayList<Set<VaultPath>>()
        val harness = createHarness(
            gitClient = gitClient,
            dispatchers = dispatchers,
            onReindex = { _, _, paths -> reindexed.add(paths.toSet()) },
        )
        harness.registry.upsert(
            VaultConfig(id = "v1", name = "Notes", rootPath = mobileDir.path, remoteUrl = remoteUri),
            setActive = true,
        )

        val result = harness.coordinator.sync("v1")
        assertTrue(result is SyncResult.Synced)
        result as SyncResult.Synced
        assertTrue(result.pushed)
        assertEquals(setOf(VaultPath.of("RemoteNote.md")), result.pulledPaths)
        assertEquals(listOf(setOf(VaultPath.of("RemoteNote.md"))), reindexed)
        assertTrue(harness.coordinator.observeState("v1").first() is VaultSyncState.Synced)

        assertTrue(gitClient.pull(desktopDir) is PullResult.Merged)
        assertEquals("# From Mobile\n", File(desktopDir, "MobileNote.md").readText())

        // Subsequent sync with no changes reports UpToDate and skips reindex.
        assertEquals(SyncResult.UpToDate, harness.coordinator.sync("v1"))
        assertEquals(1, reindexed.size)
    }

    @Test
    fun `reindexes cleanly merged files alongside conflicts during conflicting pull`() = runTest {
        val dispatchers = testDispatchers()
        val gitClient = JGitClient(dispatchers)
        val (desktopDir, mobileDir, remoteUri) = createPairedJGitRepos(gitClient)

        // Desktop edits Home.md (will conflict) AND adds CleanFromDesktop.md (will merge cleanly).
        AtomicFileWriter.writeText(File(desktopDir, "Home.md"), "# Home\nDesktop version\n")
        AtomicFileWriter.writeText(File(desktopDir, "CleanFromDesktop.md"), "# Clean Note\n")
        gitClient.commitAllIfDirty(desktopDir, "Desktop edits")
        assertEquals(PushResult.Pushed, gitClient.push(desktopDir))

        AtomicFileWriter.writeText(File(mobileDir, "Home.md"), "# Home\nMobile version\n")

        val reindexed = ArrayList<Set<VaultPath>>()
        val harness = createHarness(
            gitClient = gitClient,
            dispatchers = dispatchers,
            onReindex = { _, _, paths -> reindexed.add(paths.toSet()) },
        )
        harness.registry.upsert(VaultConfig(id = "v1", name = "Notes", rootPath = mobileDir.path, remoteUrl = remoteUri))

        val result = harness.coordinator.sync("v1")
        assertEquals(SyncResult.Conflicts(setOf(VaultPath.of("Home.md"))), result)
        assertEquals(1, reindexed.size)
        assertTrue(VaultPath.of("CleanFromDesktop.md") in reindexed.single())
        assertTrue(VaultPath.of("Home.md") in reindexed.single())
        val state = harness.coordinator.observeState("v1").first()
        assertTrue(state is VaultSyncState.Conflicts && state.paths == setOf(VaultPath.of("Home.md")))
    }

    @Test
    fun `commits locally and skips push when vault has no remote`() = runTest {
        val dispatchers = testDispatchers()
        val gitClient = JGitClient(dispatchers)
        val localDir = tempFolder.newFolder("local-only")
        gitClient.init(localDir)

        val harness = createHarness(gitClient = gitClient, dispatchers = dispatchers)
        harness.registry.upsert(VaultConfig(id = "v1", name = "Local", rootPath = localDir.path))

        assertEquals(SyncResult.UpToDate, harness.coordinator.sync("v1"))

        AtomicFileWriter.writeText(File(localDir, "Idea.md"), "# Idea\n")
        val synced = harness.coordinator.sync("v1")
        assertTrue(synced is SyncResult.Synced)
        synced as SyncResult.Synced
        assertTrue(synced.commitId != null)
        assertFalse(synced.pushed)
        assertTrue(synced.pulledPaths.isEmpty())
        assertTrue(gitClient.status(localDir).isClean)
    }

    @Test
    fun `stops before pull and push when unresolved conflicts already exist`() = runTest {
        val dispatchers = testDispatchers()
        val vaultDir = tempFolder.newFolder("existing-conflicts")
        val conflicted = setOf(VaultPath.of("Broken.md"))
        var pullCalled = false
        var pushCalled = false

        val fakeGit = FakeGitClient(
            onCommit = { _, _, _ -> CommitResult.HasConflicts(conflicted) },
            onPull = { _, _, _ ->
                pullCalled = true
                PullResult.UpToDate
            },
            onPush = { _, _ ->
                pushCalled = true
                PushResult.Pushed
            },
        )
        val harness = createHarness(gitClient = fakeGit, dispatchers = dispatchers)
        harness.registry.upsert(VaultConfig(id = "v1", name = "Main", rootPath = vaultDir.path))

        assertEquals(SyncResult.Conflicts(conflicted), harness.coordinator.sync("v1"))
        assertFalse(pullCalled)
        assertFalse(pushCalled)
        assertTrue(harness.coordinator.observeState("v1").first() is VaultSyncState.Conflicts)
    }

    @Test
    fun `retries push once on non-fast-forward and marks exhausted retries as retryable`() = runTest {
        val dispatchers = testDispatchers()
        val vaultDir = tempFolder.newFolder("vault-retry")
        val remoteAdded = VaultPath.of("Remote.md")
        var pullCalls = 0
        var pushCalls = 0
        var alwaysRejectPush = false
        val reindexed = ArrayList<Set<VaultPath>>()

        val fakeGit = FakeGitClient(
            onCommit = { _, _, _ -> CommitResult.Committed("abc123", changedCount = 1) },
            onPull = { _, _, _ ->
                pullCalls++
                if (pullCalls == 2) PullResult.Merged(setOf(remoteAdded), "abc123", "def456") else PullResult.UpToDate
            },
            onPush = { _, _ ->
                pushCalls++
                if (alwaysRejectPush || pushCalls == 1) {
                    PushResult.RejectedNonFastForward("non-fast-forward")
                } else {
                    PushResult.Pushed
                }
            },
        )
        val harness = createHarness(
            gitClient = fakeGit,
            dispatchers = dispatchers,
            onReindex = { _, _, paths -> reindexed.add(paths.toSet()) },
        )
        harness.registry.upsert(VaultConfig(id = "v1", name = "Main", rootPath = vaultDir.path))

        val succeeded = harness.coordinator.sync("v1")
        assertTrue(succeeded is SyncResult.Synced && succeeded.pushed && succeeded.pulledPaths == setOf(remoteAdded))
        assertEquals(2, pullCalls)
        assertEquals(2, pushCalls)
        assertEquals(listOf(setOf(remoteAdded)), reindexed)

        alwaysRejectPush = true
        val exhausted = harness.coordinator.sync("v1")
        assertTrue(exhausted is SyncResult.Error && exhausted.isRetryable)
        assertTrue(harness.coordinator.observeState("v1").first() is VaultSyncState.Failed)
    }

    @Test
    fun `serializes concurrent syncs per vault without blocking other vaults`() = runTest {
        val dispatchers = testDispatchers()
        val dir1 = tempFolder.newFolder("vault-1")
        val dir2 = tempFolder.newFolder("vault-2")

        val firstEnteredPull = CompletableDeferred<Unit>()
        val releaseFirstPull = CompletableDeferred<Unit>()
        val inFlightV1 = AtomicInteger(0)
        var maxOverlapV1 = 0

        val fakeGit = FakeGitClient(
            onPull = { repoDir, _, _ ->
                if (repoDir.canonicalPath == dir1.canonicalPath) {
                    maxOverlapV1 = maxOf(maxOverlapV1, inFlightV1.incrementAndGet())
                    firstEnteredPull.complete(Unit)
                    releaseFirstPull.await()
                    inFlightV1.decrementAndGet()
                }
                PullResult.UpToDate
            },
        )
        val harness = createHarness(gitClient = fakeGit, dispatchers = dispatchers)
        harness.registry.upsert(VaultConfig(id = "v1", name = "One", rootPath = dir1.path))
        harness.registry.upsert(VaultConfig(id = "v2", name = "Two", rootPath = dir2.path))

        val firstV1 = async { harness.coordinator.sync("v1") }
        firstEnteredPull.await()

        val secondV1 = async { harness.coordinator.sync("v1") }
        runCurrent()
        assertFalse(secondV1.isCompleted)

        assertEquals(SyncResult.UpToDate, harness.coordinator.sync("v2"))

        releaseFirstPull.complete(Unit)
        assertEquals(SyncResult.UpToDate, firstV1.await())
        assertEquals(SyncResult.UpToDate, secondV1.await())
        assertEquals(1, maxOverlapV1)
    }

    @Test
    fun `debounces rapid edits preserves in-flight sync and survives transient errors`() = runTest {
        val dispatchers = testDispatchers()
        val vaultDir = tempFolder.newFolder("vault-debounce")
        val pullGate = CompletableDeferred<Unit>()
        val pullEntered = CompletableDeferred<Unit>()
        var syncCount = 0

        val fakeGit = FakeGitClient(
            onPull = { _, _, _ ->
                syncCount++
                when (syncCount) {
                    1 -> {
                        pullEntered.complete(Unit)
                        pullGate.await()
                        PullResult.UpToDate
                    }
                    2 -> throw GitException(GitErrorKind.NETWORK, "Offline")
                    else -> PullResult.UpToDate
                }
            },
        )
        val harness = createHarness(gitClient = fakeGit, dispatchers = dispatchers)
        harness.prefs.setSyncDebounceMillis(2_000L)
        harness.registry.upsert(VaultConfig(id = "v1", name = "Main", rootPath = vaultDir.path))

        harness.coordinator.scheduleDebouncedSync("v1")
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(0, syncCount)

        // Second edit resets timer.
        harness.coordinator.scheduleDebouncedSync("v1")
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(0, syncCount)

        // First debounced sync starts and suspends inside pull.
        advanceTimeBy(500L)
        runCurrent()
        pullEntered.await()
        assertEquals(1, syncCount)

        // Edit while in-flight must NOT cancel current sync and schedules follow-up.
        harness.coordinator.scheduleDebouncedSync("v1")
        pullGate.complete(Unit)
        runCurrent()
        assertEquals(1, syncCount)

        // Second debounced sync throws GitException(NETWORK) — must set Failed and keep scope alive.
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(2, syncCount)
        assertTrue(harness.coordinator.observeState("v1").first() is VaultSyncState.Failed)

        // Third debounced sync still runs and recovers.
        harness.coordinator.scheduleDebouncedSync("v1")
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(3, syncCount)
        assertTrue(harness.coordinator.observeState("v1").first() is VaultSyncState.Synced)

        // Manual sync cancels pending debounce timer.
        harness.coordinator.scheduleDebouncedSync("v1")
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(SyncResult.UpToDate, harness.coordinator.sync("v1"))
        assertEquals(4, syncCount)
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(4, syncCount)

        // Disabling autoSync suppresses debounced syncs.
        harness.prefs.setAutoSyncEnabled(false)
        harness.coordinator.scheduleDebouncedSync("v1")
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(4, syncCount)
    }

    @Test
    fun `syncAll skips non-git folders and continues syncing after a vault fails`() = runTest {
        val dispatchers = testDispatchers()
        val plainDir = tempFolder.newFolder("not-a-repo")
        val failingDir = tempFolder.newFolder("failing-repo")
        val healthyDir = tempFolder.newFolder("healthy-repo")

        val fakeGit = FakeGitClient(
            onIsRepo = { dir -> dir.canonicalPath != plainDir.canonicalPath },
            onPull = { dir, _, _ ->
                if (dir.canonicalPath == failingDir.canonicalPath) {
                    throw GitException(GitErrorKind.NETWORK, "Timeout")
                }
                PullResult.UpToDate
            },
        )
        val harness = createHarness(gitClient = fakeGit, dispatchers = dispatchers)
        harness.registry.upsert(VaultConfig(id = "plain", name = "Plain", rootPath = plainDir.path))
        harness.registry.upsert(VaultConfig(id = "bad", name = "Bad", rootPath = failingDir.path))
        harness.registry.upsert(VaultConfig(id = "good", name = "Good", rootPath = healthyDir.path))

        val results = harness.coordinator.syncAll()
        assertEquals(setOf("bad", "good"), results.keys)
        val bad = results["bad"] as SyncResult.Error
        assertTrue(bad.isRetryable)
        assertEquals(SyncResult.UpToDate, results["good"])
    }

    @Test
    fun `fails fast on unreadable credentials and preserves GitException retryability`() = runTest {
        val dispatchers = testDispatchers()
        val vaultDir = tempFolder.newFolder("vault-creds")
        var gitCalled = false
        val fakeGit = FakeGitClient(
            onCommit = { _, _, _ ->
                gitCalled = true
                throw GitException(GitErrorKind.AUTH, "Unauthorized")
            },
        )
        val harness = createHarness(gitClient = fakeGit, dispatchers = dispatchers, failDecrypt = true)
        harness.registry.upsert(VaultConfig(id = "v1", name = "Main", rootPath = vaultDir.path))
        harness.credentials.save("v1", GitCredentials.HttpsToken("ghp_123"))

        val decryptError = harness.coordinator.sync("v1")
        assertTrue(decryptError is SyncResult.Error)
        decryptError as SyncResult.Error
        assertTrue(decryptError.cause is GeneralSecurityException)
        assertFalse(decryptError.isRetryable)
        assertFalse(gitCalled)

        harness.credentials.clear("v1")
        val authError = harness.coordinator.sync("v1")
        assertTrue(authError is SyncResult.Error && !authError.isRetryable)
        assertTrue(gitCalled)
    }

    @Test
    fun `restores previous state on coroutine cancellation`() = runTest {
        val dispatchers = testDispatchers()
        val vaultDir = tempFolder.newFolder("vault-cancel")
        val pullEntered = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()

        val fakeGit = FakeGitClient(
            onPull = { _, _, _ ->
                pullEntered.complete(Unit)
                neverComplete.await()
                PullResult.UpToDate
            },
        )
        val harness = createHarness(gitClient = fakeGit, dispatchers = dispatchers)
        harness.registry.upsert(VaultConfig(id = "v1", name = "Main", rootPath = vaultDir.path))

        val job = launch { harness.coordinator.sync("v1") }
        pullEntered.await()
        assertEquals(VaultSyncState.Syncing(SyncPhase.PULLING), harness.coordinator.observeState("v1").first())

        job.cancel()
        job.join()
        assertEquals(VaultSyncState.Idle, harness.coordinator.observeState("v1").first())
    }

    private suspend fun createPairedJGitRepos(gitClient: JGitClient): Triple<File, File, String> {
        val bareRemote = tempFolder.newFolder("remote.git")
        Git.init().setDirectory(bareRemote).setBare(true).setInitialBranch("main").call().close()
        val remoteUri = bareRemote.toURI().toString()

        val desktopDir = File(tempFolder.root, "desktop")
        val mobileDir = File(tempFolder.root, "mobile")
        gitClient.clone(remoteUri, desktopDir)
        AtomicFileWriter.writeText(File(desktopDir, "Home.md"), "# Home\n")
        gitClient.commitAllIfDirty(desktopDir, "Initial commit")
        assertEquals(PushResult.Pushed, gitClient.push(desktopDir))
        gitClient.clone(remoteUri, mobileDir)
        return Triple(desktopDir, mobileDir, remoteUri)
    }

    private fun TestScope.testDispatchers(): AppDispatchers {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return AppDispatchers(io = dispatcher, default = dispatcher, main = dispatcher)
    }

    private fun TestScope.createHarness(
        gitClient: GitClient,
        dispatchers: AppDispatchers,
        failDecrypt: Boolean = false,
        onReindex: suspend (String, File, Collection<VaultPath>) -> Unit = { _, _, _ -> },
    ): Harness {
        val prefs = AppPreferences(InMemoryDataStore<Preferences>(emptyPreferences()))
        val registry = VaultRegistryStore(InMemoryDataStore(VaultRegistryState()), dispatchers)
        val credentials = CredentialsStore(
            dataStore = InMemoryDataStore(emptyPreferences()),
            encrypt = { plaintext, aad -> "$aad::$plaintext" },
            decrypt = { encoded, aad ->
                if (failDecrypt) throw GeneralSecurityException("Key invalidated")
                encoded.removePrefix("$aad::")
            },
            dispatchers = dispatchers,
        )
        val coordinator = VaultSyncCoordinator(
            vaultRegistry = registry,
            credentialsStore = credentials,
            appPreferences = prefs,
            gitClient = gitClient,
            reindexPaths = onReindex,
            scope = backgroundScope,
            dispatchers = dispatchers,
            clock = fixedClock,
        )
        return Harness(prefs, registry, credentials, coordinator)
    }

    private data class Harness(
        val prefs: AppPreferences,
        val registry: VaultRegistryStore,
        val credentials: CredentialsStore,
        val coordinator: VaultSyncCoordinator,
    )

    private class FakeGitClient(
        private val onIsRepo: (File) -> Boolean = { true },
        private val onCommit: suspend (File, String, GitAuthor) -> CommitResult = { _, _, _ -> CommitResult.NothingToCommit },
        private val onPull: suspend (File, GitCredentials, GitAuthor) -> PullResult = { _, _, _ -> PullResult.UpToDate },
        private val onPush: suspend (File, GitCredentials) -> PushResult = { _, _ -> PushResult.UpToDate },
        private val onStatus: suspend (File) -> GitStatusSnapshot = {
            GitStatusSnapshot("main", GitRepoState.CLEAN, emptyList(), emptySet())
        },
    ) : GitClient {
        override suspend fun init(repoDir: File, defaultBranch: String) = Unit
        override suspend fun clone(remoteUrl: String, targetDir: File, credentials: GitCredentials, branch: String?) = Unit
        override suspend fun isGitRepository(dir: File): Boolean = onIsRepo(dir)
        override suspend fun status(repoDir: File): GitStatusSnapshot = onStatus(repoDir)
        override suspend fun commitAllIfDirty(repoDir: File, message: String, author: GitAuthor): CommitResult =
            onCommit(repoDir, message, author)
        override suspend fun pull(repoDir: File, credentials: GitCredentials, author: GitAuthor): PullResult =
            onPull(repoDir, credentials, author)
        override suspend fun push(repoDir: File, credentials: GitCredentials): PushResult =
            onPush(repoDir, credentials)
        override suspend fun diffPaths(repoDir: File, fromCommit: String?, toCommit: String): Set<VaultPath> = emptySet()
        override suspend fun log(repoDir: File, limit: Int): List<GitCommit> = emptyList()
        override suspend fun markResolved(repoDir: File, path: VaultPath, deleted: Boolean) = Unit
        override suspend fun abortMerge(repoDir: File) = Unit
    }
}
