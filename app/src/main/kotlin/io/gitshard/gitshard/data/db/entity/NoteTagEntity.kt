package io.gitshard.gitshard.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "note_tags",
    primaryKeys = ["vaultId", "notePath", "tag"],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["vaultId", "path"],
            childColumns = ["vaultId", "notePath"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["vaultId", "tag"]),
    ],
)
data class NoteTagEntity(
    val vaultId: String,
    val notePath: String,
    val tag: String,
)
