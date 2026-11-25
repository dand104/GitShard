package io.gitshard.gitshard.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "note_properties",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["vaultId", "path"],
            childColumns = ["vaultId", "notePath"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["vaultId", "notePath"]),
        Index(value = ["vaultId", "keyLower", "value"]),
    ],
)
data class NotePropertyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val vaultId: String,
    val notePath: String,
    val key: String,
    val keyLower: String,
    val value: String,
)
