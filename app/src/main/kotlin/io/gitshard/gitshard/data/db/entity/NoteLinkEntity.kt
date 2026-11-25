package io.gitshard.gitshard.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "note_links",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["vaultId", "path"],
            childColumns = ["vaultId", "sourcePath"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["vaultId", "sourcePath"]),
        Index(value = ["vaultId", "resolvedTargetPath"]),
    ],
)
data class NoteLinkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val vaultId: String,
    val sourcePath: String,
    val rawTarget: String,
    val targetNote: String,
    val resolvedTargetPath: String?,
    val subpath: String?,
    val alias: String?,
    val isEmbed: Boolean,
    val lineNumber: Int,
)
