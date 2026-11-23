package io.gitshard.gitshard.git

import io.gitshard.gitshard.core.VaultPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConflictParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `parses standard 2-way conflict and resolves all choices without extra blank lines`() {
        val raw = """
            # Title
            <<<<<<< HEAD
            Local line 1
            Local line 2
            =======
            Remote line
            >>>>>>> origin/main
            Footer
        """.trimIndent() + "\n"

        assertTrue(ConflictParser.hasConflictMarkers(raw))

        val doc = ConflictParser.parse(raw)
        assertEquals(1, doc.hunks.size)
        val hunk = doc.hunks[0]
        assertEquals("HEAD", hunk.oursLabel)
        assertEquals("origin/main", hunk.theirsLabel)
        assertEquals(listOf("Local line 1", "Local line 2"), hunk.oursLines)
        assertEquals(listOf("Remote line"), hunk.theirsLines)

        assertEquals(
            "# Title\nLocal line 1\nLocal line 2\nFooter\n",
            ConflictParser.resolve(doc, mapOf(0 to ConflictChoice.Ours)),
        )
        assertEquals(
            "# Title\nRemote line\nFooter\n",
            ConflictParser.resolve(doc, mapOf(0 to ConflictChoice.Theirs)),
        )
        assertEquals(
            "# Title\nLocal line 1\nLocal line 2\nRemote line\nFooter\n",
            ConflictParser.resolve(doc, mapOf(0 to ConflictChoice.BothOursFirst)),
        )
        assertEquals(
            "# Title\nCustom merged\nFooter\n",
            ConflictParser.resolve(doc, mapOf(0 to ConflictChoice.Custom("Custom merged"))),
        )
    }

    @Test
    fun `parses diff3 conflict handles empty side and preserves CRLF`() {
        val raw = "Start\r\n<<<<<<< HEAD\r\n||||||| base\r\nOld\r\n=======\r\nAdded on remote\r\n>>>>>>> main\r\nEnd\r\n"

        val doc = ConflictParser.parse(raw)
        assertEquals("\r\n", doc.lineSeparator)
        assertEquals(1, doc.hunks.size)
        assertEquals(emptyList<String>(), doc.hunks[0].oursLines)
        assertEquals(listOf("Old"), doc.hunks[0].baseLines)
        assertEquals(listOf("Added on remote"), doc.hunks[0].theirsLines)

        assertEquals(
            "Start\r\nEnd\r\n",
            ConflictParser.resolve(doc, mapOf(0 to ConflictChoice.Ours)),
        )
    }

    @Test
    fun `keeps unclosed conflict markers as context without data loss`() {
        val malformed = "Line 1\n<<<<<<< HEAD\nUnclosed content\n=======\nNo ending marker"

        assertFalse(ConflictParser.hasConflictMarkers(malformed))

        val doc = ConflictParser.parse(malformed)
        assertFalse(doc.hasConflicts)
        assertEquals(malformed, ConflictParser.resolve(doc, emptyMap()))
    }

    @Test
    fun `loads text binary and missing conflict files from repo`() {
        val repoDir = tempFolder.newFolder("vault")
        File(repoDir, "note.md").writeText("<<<<<<< HEAD\nA\n=======\nB\n>>>>>>> main\n")
        File(repoDir, "image.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x00, 0x47))

        val loaded = ConflictParser.loadFiles(
            repoDir = repoDir,
            paths = listOf(
                VaultPath.of("missing.md"),
                VaultPath.of("image.png"),
                VaultPath.of("note.md"),
            ),
        )

        assertEquals(3, loaded.size)
        assertEquals(VaultPath.of("image.png"), loaded[0].path)
        assertEquals(ConflictKind.BINARY_OR_UNPARSEABLE, loaded[0].kind)
        assertNull(loaded[0].document)

        assertEquals(VaultPath.of("missing.md"), loaded[1].path)
        assertEquals(ConflictKind.DELETED_OR_MISSING, loaded[1].kind)

        assertEquals(VaultPath.of("note.md"), loaded[2].path)
        assertEquals(ConflictKind.CONTENT, loaded[2].kind)
        assertEquals(1, loaded[2].document?.hunks?.size)
    }

    @Test
    fun `treats oversized conflict files as unparseable without loading into memory`() {
        val repoDir = tempFolder.newFolder("oversized-vault")
        val largeText = "<<<<<<< HEAD\n" + "a".repeat(256) + "\n=======\n" + "b".repeat(256) + "\n>>>>>>> main\n"
        File(repoDir, "huge.md").writeText(largeText)

        val loaded = ConflictParser.loadFile(repoDir, VaultPath.of("huge.md"), maxFileBytes = 128L)

        assertEquals(VaultPath.of("huge.md"), loaded.path)
        assertEquals(ConflictKind.BINARY_OR_UNPARSEABLE, loaded.kind)
        assertNull(loaded.document)
    }
}
