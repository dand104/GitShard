package io.gitshard.gitshard.data

import io.gitshard.gitshard.core.AppDispatchers
import io.gitshard.gitshard.core.VaultPath
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `scans markdown and attachments while skipping hidden directories`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = VaultStorage(AppDispatchers(io = dispatcher, default = dispatcher, main = dispatcher))
        val vaultRoot = tempFolder.newFolder("vault")

        storage.writeText(vaultRoot, VaultPath.of("Home.md"), "# Home")
        storage.writeText(vaultRoot, VaultPath.of("Projects/Spec.md"), "# Spec")
        File(vaultRoot, "Assets/diagram.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        File(vaultRoot, ".git/HEAD").apply {
            parentFile?.mkdirs()
            writeText("ref: refs/heads/main\n")
        }
        File(vaultRoot, ".obsidian/app.json").apply {
            parentFile?.mkdirs()
            writeText("{}")
        }

        val scan = storage.scanVault(vaultRoot)
        assertEquals(
            setOf(VaultPath.of("Home.md"), VaultPath.of("Projects/Spec.md")),
            scan.markdownFiles.map { it.path }.toSet(),
        )
        assertTrue(VaultPath.of("Assets/diagram.png") in scan.allPaths)
        assertTrue(scan.allPaths.none { it.isHidden })

        storage.rename(vaultRoot, VaultPath.of("Projects/Spec.md"), VaultPath.of("Archive/Spec.md"))
        assertFalse(storage.exists(vaultRoot, VaultPath.of("Projects/Spec.md")))
        assertEquals("# Spec", storage.readText(vaultRoot, VaultPath.of("Archive/Spec.md")))
    }

    @Test
    fun `rename rejects colliding destination and treats same path as no-op`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = VaultStorage(AppDispatchers(io = dispatcher, default = dispatcher, main = dispatcher))
        val vaultRoot = tempFolder.newFolder("rename-vault")

        val pathA = VaultPath.of("A.md")
        val pathB = VaultPath.of("B.md")
        storage.writeText(vaultRoot, pathA, "# A")
        storage.writeText(vaultRoot, pathB, "# B")

        storage.rename(vaultRoot, pathA, pathA)
        assertEquals("# A", storage.readText(vaultRoot, pathA))

        val failure = runCatching {
            storage.rename(vaultRoot, pathA, pathB)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("# A", storage.readText(vaultRoot, pathA))
        assertEquals("# B", storage.readText(vaultRoot, pathB))
    }
}
