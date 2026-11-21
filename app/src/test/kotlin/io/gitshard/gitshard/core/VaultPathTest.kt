package io.gitshard.gitshard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultPathTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `normalizes slashes and dot segments`() {
        val path = VaultPath.of("""/Notes\./Daily//2026-11-21.md/""")
        assertEquals("Notes/Daily/2026-11-21.md", path.value)
        assertEquals("2026-11-21.md", path.name)
        assertEquals("2026-11-21", path.nameWithoutExtension)
        assertEquals("md", path.extension)
        assertEquals("Notes/Daily", path.parent?.value)
        assertTrue(path.isMarkdown)
        assertFalse(path.isHidden)
    }

    @Test
    fun `rejects traversal blank and nul byte`() {
        assertNull(VaultPath.ofOrNull(""))
        assertNull(VaultPath.ofOrNull("///"))
        assertNull(VaultPath.ofOrNull("."))
        assertNull(VaultPath.ofOrNull("../secret.md"))
        assertNull(VaultPath.ofOrNull("Notes/../../etc/passwd"))
        assertNull(VaultPath.ofOrNull("Notes/bad\u0000.md"))
    }

    @Test
    fun `detects hidden paths and root parent`() {
        val rootNote = VaultPath.of("README.md")
        assertNull(rootNote.parent)
        assertEquals("", rootNote.folderPath)

        assertTrue(VaultPath.of(".obsidian/app.json").isHidden)
        assertTrue(VaultPath.of("folder/.gitkeep").isHidden)
    }

    @Test
    fun `relativize and resolve stay inside vault root`() {
        val vaultRoot = tempFolder.newFolder("vault")
        val notePath = VaultPath.of("Projects/GitShard.md")
        val resolved = notePath.resolveIn(vaultRoot)

        assertEquals(File(vaultRoot, "Projects/GitShard.md").canonicalFile, resolved)
        assertEquals(notePath, VaultPath.relativizeOrNull(vaultRoot, resolved))
        assertNull(VaultPath.relativizeOrNull(vaultRoot, tempFolder.root))
    }
}
