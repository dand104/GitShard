package io.gitshard.gitshard.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "note_tasks",
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
        Index(value = ["vaultId", "isCompleted", "dueDate"]),
    ],
)
data class NoteTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val vaultId: String,
    val notePath: String,
    val lineNumber: Int,
    val text: String,
    val statusChar: String,
    val isCompleted: Boolean,
    val dueDate: String?,
    val tagsPacked: String,
) {
    companion object {
        fun packTags(tags: Collection<String>): String =
            if (tags.isEmpty()) "" else tags.joinToString(separator = "|", prefix = "|", postfix = "|")

        fun unpackTags(packed: String): List<String> =
            if (packed.isEmpty()) emptyList() else packed.trim('|').split('|').filter { it.isNotEmpty() }
    }
}
