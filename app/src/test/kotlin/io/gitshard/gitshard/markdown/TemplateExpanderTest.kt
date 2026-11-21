package io.gitshard.gitshard.markdown

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

class TemplateExpanderTest {

    @Test
    fun `expands title date time moment patterns and keeps unknown placeholders`() {
        val expander = TemplateExpander(locale = Locale.US)
        val fixedNow = ZonedDateTime.of(2026, 11, 21, 9, 5, 30, 0, ZoneOffset.UTC)

        val template = """
            # {{title}}
            Date: {{date}} / {{date:DD.MM.YYYY [at] HH:mm}}
            Time: {{time}}
            Keep: {{custom_var}}
        """.trimIndent()

        val result = expander.expand(template, title = "Daily Note", now = fixedNow)

        assertEquals(
            """
            # Daily Note
            Date: 2026-11-21 / 21.11.2026 at 09:05
            Time: 09:05
            Keep: {{custom_var}}
            """.trimIndent(),
            result,
        )
    }
}
