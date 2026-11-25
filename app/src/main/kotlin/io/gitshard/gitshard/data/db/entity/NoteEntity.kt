package io.gitshard.gitshard.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "notes",
    primaryKeys = ["vaultId", "path"],
    indices = [
        Index(value = ["vaultId", "folderPath"]),
        Index(value = ["vaultId", "lastModified"]),
        Index(value = ["vaultId", "ftsRowId"]),
    ],
)
data class NoteEntity(
    val vaultId: String,
    val path: String,
    val folderPath: String,
    val name: String,
    val title: String,
    val snippet: String,
    val lastModified: Long,
    val fileSize: Long,
    val wordCount: Int,
    val ftsRowId: Long = 0L,
)
