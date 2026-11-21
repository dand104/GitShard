package io.gitshard.gitshard.markdown

import io.gitshard.gitshard.core.VaultPath
import org.junit.Assert.assertEquals
import org.junit.Test

class WikilinkResolverTest {

    private val index = VaultPathIndex(
        listOf(
            VaultPath.of("Index.md"),
            VaultPath.of("Work/Index.md"),
            VaultPath.of("Work/Backend/API.md"),
            VaultPath.of("Personal/Notes/Book.md"),
            VaultPath.of("Assets/diagram.png"),
        ),
    )

    @Test
    fun `prefers closest note on duplicate short names`() {
        val source = VaultPath.of("Work/Backend/API.md")
        val resolved = WikilinkResolver.resolve(source, "Index", index = index)
        assertEquals(
            LinkResolution.Resolved(VaultPath.of("Work/Index.md"), subpath = null),
            resolved,
        )
    }

    @Test
    fun `resolves relative paths attachments and self headings`() {
        val source = VaultPath.of("Work/Backend/API.md")

        assertEquals(
            LinkResolution.Resolved(VaultPath.of("Work/Index.md"), "#Goals"),
            WikilinkResolver.resolve(source, "../Index", subpath = "#Goals", index = index),
        )
        assertEquals(
            LinkResolution.Resolved(VaultPath.of("Assets/diagram.png"), null),
            WikilinkResolver.resolve(source, "diagram.png", isEmbed = true, index = index),
        )
        assertEquals(
            LinkResolution.SelfHeading(source, "#Auth"),
            WikilinkResolver.resolve(source, "", subpath = "#Auth", index = index),
        )
    }

    @Test
    fun `returns suggested markdown path for unresolved link`() {
        val source = VaultPath.of("Work/Index.md")
        assertEquals(
            LinkResolution.Unresolved(VaultPath.of("Ideas/New Note.md"), null),
            WikilinkResolver.resolve(source, "Ideas/New Note", index = index),
        )
    }
}
