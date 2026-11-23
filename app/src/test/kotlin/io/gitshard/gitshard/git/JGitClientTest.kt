package io.gitshard.gitshard.git

import io.gitshard.gitshard.core.AppDispatchers
import io.gitshard.gitshard.core.AtomicFileWriter
import io.gitshard.gitshard.core.VaultPath
import io.gitshard.gitshard.git.jgit.JGitClient
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JGitClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var previousSystemReader: SystemReader? = null

    @Before
    fun isolateJGitEnvironment() {
        val current = SystemReader.getInstance()
        previousSystemReader = current
        SystemReader.setInstance(IsolatedSystemReader(current, tempFolder.newFolder("jgit-env")))
    }

    @After
    fun restoreJGitEnvironment() {
        previousSystemReader?.let(SystemReader::setInstance)
    }

    @Test
    fun `init commit delete and log work end to end`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = JGitClient(AppDispatchers(io = dispatcher, default = dispatcher, main = dispatcher))
        val repoDir = tempFolder.newFolder("local-vault")

        client.init(repoDir)
        assertTrue(client.isGitRepository(repoDir))
        assertEquals(CommitResult.NothingToCommit, client.commitAllIfDirty(repoDir, "Empty"))

        val noteA = File(repoDir, "Notes/Alpha.md")
        val noteB = File(repoDir, "Notes/Beta.md")
        AtomicFileWriter.writeText(noteA, "# Alpha\n")
        AtomicFileWriter.writeText(noteB, "# Beta\n")

        val commit1 = client.commitAllIfDirty(repoDir, "Add initial notes")
        assertTrue(commit1 is CommitResult.Committed)
        assertTrue(client.status(repoDir).isClean)

        assertTrue(noteB.delete())
        val commit2 = client.commitAllIfDirty(repoDir, "Remove Beta")
        assertTrue(commit2 is CommitResult.Committed)
        assertTrue(client.status(repoDir).isClean)

        val commits = client.log(repoDir, limit = 10)
        assertEquals(2, commits.size)
        assertEquals("Remove Beta", commits[0].shortMessage)
        assertEquals("Add initial notes", commits[1].shortMessage)

        val diff = client.diffPaths(repoDir, commits[1].id, commits[0].id)
        assertEquals(setOf(VaultPath.of("Notes/Beta.md")), diff)
    }

    @Test
    fun `clone push pull fast-forward and merge conflict resolution flow`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = JGitClient(AppDispatchers(io = dispatcher, default = dispatcher, main = dispatcher))

        val bareRemote = tempFolder.newFolder("remote.git")
        Git.init().setDirectory(bareRemote).setBare(true).setInitialBranch("main").call().close()
        val remoteUri = bareRemote.toURI().toString()

        val clientA = File(tempFolder.root, "vault-a")
        val clientB = File(tempFolder.root, "vault-b")

        client.clone(remoteUri, clientA)
        assertEquals("main", client.status(clientA).branch)
        AtomicFileWriter.writeText(File(clientA, "Home.md"), "# Home\nLine 1\n")
        assertTrue(client.commitAllIfDirty(clientA, "Init vault") is CommitResult.Committed)
        assertEquals(PushResult.Pushed, client.push(clientA))

        client.clone(remoteUri, clientB)

        AtomicFileWriter.writeText(File(clientA, "Daily/2026-11-23.md"), "# Today\n")
        assertTrue(client.commitAllIfDirty(clientA, "Add daily note") is CommitResult.Committed)
        assertEquals(PushResult.Pushed, client.push(clientA))

        val ffPull = client.pull(clientB)
        assertTrue(ffPull is PullResult.Merged)
        ffPull as PullResult.Merged
        assertEquals(setOf(VaultPath.of("Daily/2026-11-23.md")), ffPull.changedPaths)

        Git.open(clientB).use { git ->
            git.repository.config.setBoolean("commit", null, "gpgsign", true)
            git.repository.config.save()
        }
        AtomicFileWriter.writeText(File(clientA, "FromA.md"), "# A\n")
        client.commitAllIfDirty(clientA, "Add FromA")
        assertEquals(PushResult.Pushed, client.push(clientA))

        AtomicFileWriter.writeText(File(clientB, "FromB.md"), "# B\n")
        client.commitAllIfDirty(clientB, "Add FromB")

        val mergeAuthor = GitAuthor(name = "Alice", email = "alice@example.com")
        val cleanMergePull = client.pull(clientB, author = mergeAuthor)
        assertTrue(cleanMergePull is PullResult.Merged)
        cleanMergePull as PullResult.Merged
        assertEquals(setOf(VaultPath.of("FromA.md")), cleanMergePull.changedPaths)

        val latestCommit = client.log(clientB, limit = 1).single()
        assertEquals("Alice", latestCommit.authorName)
        assertEquals("alice@example.com", latestCommit.authorEmail)
        assertEquals(PushResult.Pushed, client.push(clientB))

        assertTrue(client.pull(clientA) is PullResult.Merged)

        AtomicFileWriter.writeText(File(clientA, "Home.md"), "# Home\nFrom A\n")
        client.commitAllIfDirty(clientA, "Edit from A")
        assertEquals(PushResult.Pushed, client.push(clientA))

        AtomicFileWriter.writeText(File(clientB, "Home.md"), "# Home\nFrom B\n")
        client.commitAllIfDirty(clientB, "Edit from B")

        val rejectedPush = client.push(clientB)
        assertTrue(rejectedPush is PushResult.RejectedNonFastForward)

        val conflictPull = client.pull(clientB)
        assertTrue(conflictPull is PullResult.Conflicts)
        conflictPull as PullResult.Conflicts
        assertEquals(setOf(VaultPath.of("Home.md")), conflictPull.paths)

        val conflictFiles = ConflictParser.loadFiles(clientB, conflictPull.paths)
        val conflictFile = conflictFiles.single()
        assertEquals(VaultPath.of("Home.md"), conflictFile.path)
        assertEquals(ConflictKind.CONTENT, conflictFile.kind)

        val resolvedText = ConflictParser.resolve(
            document = requireNotNull(conflictFile.document),
            choices = mapOf(0 to ConflictChoice.BothOursFirst),
        )
        assertEquals("# Home\nFrom B\nFrom A\n", resolvedText)

        AtomicFileWriter.writeText(File(clientB, "Home.md"), resolvedText)
        client.markResolved(clientB, VaultPath.of("Home.md"))

        val mergeCommit = client.commitAllIfDirty(clientB, "Resolve Home.md conflict")
        assertTrue(mergeCommit is CommitResult.Committed)
        assertEquals(PushResult.Pushed, client.push(clientB))
        assertTrue(client.status(clientB).isClean)
    }

    @Test
    fun `read-only operations do not mutate existing repository config`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = JGitClient(AppDispatchers(io = dispatcher, default = dispatcher, main = dispatcher))
        val existingRepo = tempFolder.newFolder("external-repo")

        Git.init().setDirectory(existingRepo).setInitialBranch("main").call().use { git ->
            git.repository.config.setString("core", null, "autocrlf", "input")
            git.repository.config.save()
        }
        val configFile = File(existingRepo, ".git/config")
        val beforeConfig = configFile.readText()

        client.status(existingRepo)
        client.log(existingRepo)

        assertEquals(beforeConfig, configFile.readText())
    }

    @Test
    fun `pulls into unborn local branch after remote receives initial commit`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = JGitClient(AppDispatchers(io = dispatcher, default = dispatcher, main = dispatcher))

        val bareRemote = tempFolder.newFolder("empty-remote.git")
        Git.init().setDirectory(bareRemote).setBare(true).setInitialBranch("main").call().close()
        val remoteUri = bareRemote.toURI().toString()

        val mobileClient = File(tempFolder.root, "mobile-empty")
        val desktopClient = File(tempFolder.root, "desktop")

        client.clone(remoteUri, mobileClient)
        client.clone(remoteUri, desktopClient)

        AtomicFileWriter.writeText(File(desktopClient, "Welcome.md"), "# Welcome\n")
        assertTrue(client.commitAllIfDirty(desktopClient, "Initial desktop commit") is CommitResult.Committed)
        assertEquals(PushResult.Pushed, client.push(desktopClient))

        val pullResult = client.pull(mobileClient)
        assertTrue(pullResult is PullResult.Merged)
        pullResult as PullResult.Merged
        assertEquals(setOf(VaultPath.of("Welcome.md")), pullResult.changedPaths)
        assertEquals("# Welcome\n", File(mobileClient, "Welcome.md").readText())
        assertTrue(client.status(mobileClient).isClean)
    }

    @Test
    fun `pushes and pulls using non-origin remote name`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = JGitClient(AppDispatchers(io = dispatcher, default = dispatcher, main = dispatcher))

        val bareRemote = tempFolder.newFolder("custom-remote.git")
        Git.init().setDirectory(bareRemote).setBare(true).setInitialBranch("main").call().close()
        val remoteUri = bareRemote.toURI().toString()

        val localA = tempFolder.newFolder("local-a")
        client.init(localA, defaultBranch = "main")
        Git.open(localA).use { git ->
            git.remoteAdd().setName("vault").setUri(URIish(remoteUri)).call()
        }

        AtomicFileWriter.writeText(File(localA, "Notes.md"), "# Notes\n")
        assertTrue(client.commitAllIfDirty(localA, "First push to vault") is CommitResult.Committed)
        assertEquals(PushResult.Pushed, client.push(localA))

        val localB = tempFolder.newFolder("local-b")
        client.init(localB, defaultBranch = "main")
        Git.open(localB).use { git ->
            git.remoteAdd().setName("vault").setUri(URIish(remoteUri)).call()
        }

        val pullResult = client.pull(localB)
        assertTrue(pullResult is PullResult.Merged)
        pullResult as PullResult.Merged
        assertEquals(setOf(VaultPath.of("Notes.md")), pullResult.changedPaths)
        assertEquals("# Notes\n", File(localB, "Notes.md").readText())
    }

    private class IsolatedSystemReader(
        delegate: SystemReader,
        private val isolatedDir: File,
    ) : SystemReader.Delegate(delegate) {

        override fun openUserConfig(parent: Config?, fs: FS): FileBasedConfig =
            FileBasedConfig(parent, File(isolatedDir, "user.gitconfig"), fs)

        override fun openSystemConfig(parent: Config?, fs: FS): FileBasedConfig =
            FileBasedConfig(parent, File(isolatedDir, "system.gitconfig"), fs)

        override fun openJGitConfig(parent: Config?, fs: FS): FileBasedConfig =
            FileBasedConfig(parent, File(isolatedDir, "jgit.config"), fs)

        override fun getenv(variable: String): String? = when (variable) {
            "GIT_CONFIG_GLOBAL",
            "GIT_CONFIG_SYSTEM",
            "GIT_CONFIG_PARAMETERS",
            "GIT_CONFIG_COUNT",
            "XDG_CONFIG_HOME",
            -> null
            "HOME" -> isolatedDir.absolutePath
            else -> super.getenv(variable)
        }
    }
}
