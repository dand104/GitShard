package io.gitshard.gitshard.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BaseParserTest {

    private val parser = BaseParser()

    @Test
    fun `parses frontmatter links embeds tags headings tasks and excludes code`() {
        val md = """
            ---
            title: GitShard Architecture
            aliases: [GS, "Git Shard"]
            tags:
              - Project/Android
              - #Kotlin
            status: active # inline comment
            ---
            # Main Heading

            See [[Core/VaultPath|Path]] and [[#Section 2]], as well as ![[diagram.png]].
            In table: [[Room\|Database]] with tag #Work/DB and [site](https://example.com/#anchor).
            Not tags: #12345 foo#bar `#code-tag` %% #comment-tag [[Ignored]] %%

            ```kotlin
            // [[NotALink]] #not-a-tag
            // - [ ] Not a task
            ```

            ## Section 2
            - [ ] Build parser #backend 📅 2026-11-25
            - [x] Configure Gradle [due:: 2026-11-20]
            - [/] In-progress Obsidian status
        """.trimIndent()

        val parsed = parser.parse(md)

        assertEquals("GitShard Architecture", parsed.title)
        assertEquals(listOf("GS", "Git Shard"), parsed.frontmatter.aliases)
        assertTrue(parsed.frontmatter.properties.any { it.key == "status" && it.value == "active" })

        assertEquals(
            setOf("project/android", "kotlin", "work/db", "backend"),
            parsed.tags,
        )

        assertEquals(2, parsed.headings.size)
        assertEquals(NoteHeading(1, "Main Heading", lineNumber = 9), parsed.headings[0])
        assertEquals(NoteHeading(2, "Section 2", lineNumber = 20), parsed.headings[1])

        assertEquals(4, parsed.links.size)
        assertEquals(
            NoteLink("Core/VaultPath", "Core/VaultPath", null, "Path", isEmbed = false, lineNumber = 11),
            parsed.links[0],
        )
        assertEquals(
            NoteLink("#Section 2", "", "#Section 2", null, isEmbed = false, lineNumber = 11),
            parsed.links[1],
        )
        assertEquals(
            NoteLink("diagram.png", "diagram.png", null, null, isEmbed = true, lineNumber = 11),
            parsed.links[2],
        )
        assertEquals(
            NoteLink("Room", "Room", null, "Database", isEmbed = false, lineNumber = 12),
            parsed.links[3],
        )

        assertEquals(3, parsed.tasks.size)
        assertEquals(21, parsed.tasks[0].lineNumber)
        assertFalse(parsed.tasks[0].isCompleted)
        assertEquals(LocalDate.of(2026, 11, 25), parsed.tasks[0].dueDate)
        assertEquals(setOf("backend"), parsed.tasks[0].tags)

        assertEquals(22, parsed.tasks[1].lineNumber)
        assertTrue(parsed.tasks[1].isCompleted)
        assertEquals(LocalDate.of(2026, 11, 20), parsed.tasks[1].dueDate)

        assertEquals('/', parsed.tasks[2].statusChar)
        assertFalse(parsed.tasks[2].isCompleted)
    }

    @Test
    fun `does not confuse thematic break with frontmatter`() {
        val md = """
            ---
            Just a plain paragraph without YAML
            ---
        """.trimIndent()

        val parsed = parser.parse(md)
        assertEquals(Frontmatter.EMPTY, parsed.frontmatter)
        assertTrue(parsed.snippet.contains("Just a plain paragraph"))
    }

    @Test
    fun `ignores headings and tasks inside obsidian comments and excluded spans in tasks`() {
        val md = """
            ---
            tags:
            - unindented-tag
            ---
            %%
            # Hidden Heading
            - [ ] Hidden task #hidden-tag
            %%

            # Visible Heading
            - [ ] Check `foo #code-tag` and %% #comment-tag %% #real-tag
            > [!todo] Callout
            > - [ ] Task inside callout #callout-tag
        """.trimIndent()

        val parsed = parser.parse(md)

        assertEquals("Visible Heading", parsed.title)
        assertEquals(listOf(NoteHeading(1, "Visible Heading", lineNumber = 10)), parsed.headings)
        assertEquals(
            setOf("unindented-tag", "real-tag", "callout-tag"),
            parsed.tags,
        )
        assertEquals(2, parsed.tasks.size)
        assertEquals(11, parsed.tasks[0].lineNumber)
        assertEquals(setOf("real-tag"), parsed.tasks[0].tags)
        assertEquals(13, parsed.tasks[1].lineNumber)
        assertEquals(setOf("callout-tag"), parsed.tasks[1].tags)
    }

    @Test
    fun `ignores due dates inside inline code and obsidian comments`() {
        val md = """
            - [ ] Document format `📅 2026-11-25`
            - [ ] Deferred %% [due:: 2026-11-26] %%
            - [ ] Replace `[due:: 2026-01-01]` before 📅 2026-12-01
        """.trimIndent()

        val parsed = parser.parse(md)

        assertEquals(3, parsed.tasks.size)
        assertNull(parsed.tasks[0].dueDate)
        assertNull(parsed.tasks[1].dueDate)
        assertEquals(LocalDate.of(2026, 12, 1), parsed.tasks[2].dueDate)
    }
}
