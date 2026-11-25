package io.gitshard.gitshard.data.db.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "note_fts")
data class NoteFtsEntity(
    val path: String,
    val title: String,
    val headings: String,
    val aliases: String,
    val tags: String,
    val body: String,
)
