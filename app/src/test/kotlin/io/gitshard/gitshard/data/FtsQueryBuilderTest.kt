package io.gitshard.gitshard.data

import io.gitshard.gitshard.data.db.FtsQueryBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsQueryBuilderTest {

    @Test
    fun `sanitizes punctuation quotes and preserves unicode tokens`() {
        assertEquals(
            "café* room* 2026*",
            FtsQueryBuilder.buildPrefixQuery("Café: \"Room\" (2026)!".lowercase()),
        )
        assertEquals("foo* bar*", FtsQueryBuilder.buildPrefixQuery("foo-bar"))
        assertEquals("GitShard* FTS4*", FtsQueryBuilder.buildPrefixQuery("  GitShard   FTS4  "))
    }

    @Test
    fun `returns null when query has no searchable tokens`() {
        assertNull(FtsQueryBuilder.buildPrefixQuery(""))
        assertNull(FtsQueryBuilder.buildPrefixQuery("   --- \"()\" * : "))
    }
}
