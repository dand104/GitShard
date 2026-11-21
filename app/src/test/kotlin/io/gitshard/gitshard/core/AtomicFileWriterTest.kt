package io.gitshard.gitshard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicFileWriterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `writes creates parents overwrites and leaves no temp files`() {
        val target = File(tempFolder.root, "nested/dir/note.md")
        AtomicFileWriter.writeText(target, "# Hello\nFirst version")
        assertEquals("# Hello\nFirst version", target.readText())

        AtomicFileWriter.writeText(target, "# Hello\nSecond version")
        assertEquals("# Hello\nSecond version", target.readText())

        val siblings = target.parentFile!!.listFiles().orEmpty()
        assertEquals(1, siblings.size)
        assertTrue(siblings.none { it.name.endsWith(".tmp") })
    }
}
