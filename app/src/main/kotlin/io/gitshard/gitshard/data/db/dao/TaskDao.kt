package io.gitshard.gitshard.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import io.gitshard.gitshard.data.db.entity.NoteTaskEntity
import kotlinx.coroutines.flow.Flow

data class TaskItem(
    val id: Long,
    val vaultId: String,
    val notePath: String,
    val noteTitle: String,
    val lineNumber: Int,
    val text: String,
    val statusChar: String,
    val isCompleted: Boolean,
    val dueDate: String?,
    val tagsPacked: String,
) {
    val tags: List<String>
        get() = NoteTaskEntity.unpackTags(tagsPacked)
}

@Dao
interface TaskDao {

    @Query(
        """
        SELECT t.id, t.vaultId, t.notePath, n.title AS noteTitle,
               t.lineNumber, t.text, t.statusChar, t.isCompleted, t.dueDate, t.tagsPacked
        FROM note_tasks t
        INNER JOIN notes n ON t.vaultId = n.vaultId AND t.notePath = n.path
        WHERE t.vaultId = :vaultId
          AND (:includeCompleted = 1 OR t.isCompleted = 0)
          AND (:dueOnOrBefore IS NULL OR (t.dueDate IS NOT NULL AND t.dueDate <= :dueOnOrBefore))
          AND (:tag IS NULL
               OR INSTR(t.tagsPacked, '|' || :tag || '|') > 0
               OR INSTR(t.tagsPacked, '|' || :tag || '/') > 0)
        ORDER BY t.isCompleted ASC,
                 CASE WHEN t.dueDate IS NULL THEN 1 ELSE 0 END ASC,
                 t.dueDate ASC,
                 t.notePath ASC,
                 t.lineNumber ASC
        """,
    )
    fun observeTasks(
        vaultId: String,
        includeCompleted: Boolean = true,
        dueOnOrBefore: String? = null,
        tag: String? = null,
    ): Flow<List<TaskItem>>

    @Query(
        """
        SELECT * FROM note_tasks
        WHERE vaultId = :vaultId AND notePath = :notePath
        ORDER BY lineNumber ASC
        """,
    )
    fun observeTasksForNote(vaultId: String, notePath: String): Flow<List<NoteTaskEntity>>

    @Query("SELECT COUNT(*) FROM note_tasks WHERE vaultId = :vaultId AND isCompleted = 0")
    fun observeOpenTaskCount(vaultId: String): Flow<Int>
}
