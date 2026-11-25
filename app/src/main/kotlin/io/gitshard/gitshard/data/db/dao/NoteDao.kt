package io.gitshard.gitshard.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.gitshard.gitshard.data.db.entity.NoteEntity
import io.gitshard.gitshard.data.db.entity.NoteFtsEntity
import io.gitshard.gitshard.data.db.entity.NoteLinkEntity
import io.gitshard.gitshard.data.db.entity.NotePropertyEntity
import io.gitshard.gitshard.data.db.entity.NoteTagEntity
import io.gitshard.gitshard.data.db.entity.NoteTaskEntity
import kotlinx.coroutines.flow.Flow

data class NoteSyncMeta(
    val path: String,
    val lastModified: Long,
    val fileSize: Long,
)

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes WHERE vaultId = :vaultId ORDER BY path COLLATE NOCASE ASC")
    fun observeAllNotes(vaultId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE vaultId = :vaultId ORDER BY lastModified DESC LIMIT :limit")
    fun observeRecentNotes(vaultId: String, limit: Int = 20): Flow<List<NoteEntity>>

    @Query(
        """
        SELECT * FROM notes
        WHERE vaultId = :vaultId AND folderPath = :folderPath
        ORDER BY name COLLATE NOCASE ASC
        """,
    )
    fun observeNotesInFolder(vaultId: String, folderPath: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE vaultId = :vaultId AND path = :path")
    fun observeNote(vaultId: String, path: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE vaultId = :vaultId AND path = :path")
    suspend fun getNote(vaultId: String, path: String): NoteEntity?

    @Query("SELECT ftsRowId FROM notes WHERE vaultId = :vaultId AND path = :path")
    suspend fun getFtsRowId(vaultId: String, path: String): Long?

    @Query("SELECT path, lastModified, fileSize FROM notes WHERE vaultId = :vaultId")
    suspend fun getSyncMeta(vaultId: String): List<NoteSyncMeta>

    @Query("SELECT path FROM notes WHERE vaultId = :vaultId")
    suspend fun getAllPaths(vaultId: String): List<String>

    @Query(
        """
        SELECT * FROM note_properties
        WHERE vaultId = :vaultId AND notePath = :notePath
        ORDER BY id ASC
        """,
    )
    fun observePropertiesForNote(vaultId: String, notePath: String): Flow<List<NotePropertyEntity>>

    @Query(
        """
        SELECT DISTINCT keyLower FROM note_properties
        WHERE vaultId = :vaultId
        ORDER BY keyLower ASC
        """,
    )
    fun observePropertyKeys(vaultId: String): Flow<List<String>>

    @Query(
        """
        SELECT notes.* FROM notes
        INNER JOIN note_fts ON notes.ftsRowId = note_fts.rowid
        WHERE notes.vaultId = :vaultId
          AND note_fts MATCH :ftsQuery
          AND (:tag IS NULL OR EXISTS (
              SELECT 1 FROM note_tags t
              WHERE t.vaultId = notes.vaultId AND t.notePath = notes.path
                AND (t.tag = :tag OR (LENGTH(t.tag) > LENGTH(:tag) AND SUBSTR(t.tag, 1, LENGTH(:tag) + 1) = :tag || '/'))
          ))
          AND (:propertyKey IS NULL OR EXISTS (
              SELECT 1 FROM note_properties p
              WHERE p.vaultId = notes.vaultId AND p.notePath = notes.path
                AND p.keyLower = :propertyKey
                AND (:propertyValue IS NULL OR INSTR(p.value, :propertyValue) > 0)
          ))
        ORDER BY notes.lastModified DESC
        LIMIT :limit
        """,
    )
    fun searchFts(
        vaultId: String,
        ftsQuery: String,
        tag: String? = null,
        propertyKey: String? = null,
        propertyValue: String? = null,
        limit: Int = 100,
    ): Flow<List<NoteEntity>>

    @Query(
        """
        SELECT notes.* FROM notes
        WHERE notes.vaultId = :vaultId
          AND (:tag IS NULL OR EXISTS (
              SELECT 1 FROM note_tags t
              WHERE t.vaultId = notes.vaultId AND t.notePath = notes.path
                AND (t.tag = :tag OR (LENGTH(t.tag) > LENGTH(:tag) AND SUBSTR(t.tag, 1, LENGTH(:tag) + 1) = :tag || '/'))
          ))
          AND (:propertyKey IS NULL OR EXISTS (
              SELECT 1 FROM note_properties p
              WHERE p.vaultId = notes.vaultId AND p.notePath = notes.path
                AND p.keyLower = :propertyKey
                AND (:propertyValue IS NULL OR INSTR(p.value, :propertyValue) > 0)
          ))
        ORDER BY notes.lastModified DESC
        LIMIT :limit
        """,
    )
    fun filterNotes(
        vaultId: String,
        tag: String? = null,
        propertyKey: String? = null,
        propertyValue: String? = null,
        limit: Int = 100,
    ): Flow<List<NoteEntity>>

    fun suggestNotes(vaultId: String, query: String, limit: Int = 20): Flow<List<NoteEntity>> {
        val escaped = query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return suggestNotesEscaped(vaultId, escaped, limit)
    }

    @Query(
        """
        SELECT * FROM notes
        WHERE vaultId = :vaultId
          AND (title LIKE '%' || :escapedQuery || '%' ESCAPE '\'
               OR path LIKE '%' || :escapedQuery || '%' ESCAPE '\')
        ORDER BY lastModified DESC
        LIMIT :limit
        """,
    )
    fun suggestNotesEscaped(vaultId: String, escapedQuery: String, limit: Int = 20): Flow<List<NoteEntity>>

    @Upsert
    suspend fun upsertNote(note: NoteEntity)

    @Insert
    suspend fun insertFts(fts: NoteFtsEntity): Long

    @Insert
    suspend fun insertLinks(links: List<NoteLinkEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<NoteTagEntity>)

    @Insert
    suspend fun insertTasks(tasks: List<NoteTaskEntity>)

    @Insert
    suspend fun insertProperties(properties: List<NotePropertyEntity>)

    @Query("DELETE FROM note_fts WHERE rowid = :rowId")
    suspend fun deleteFtsByRowId(rowId: Long)

    @Query("DELETE FROM note_links WHERE vaultId = :vaultId AND sourcePath = :notePath")
    suspend fun deleteLinksForNote(vaultId: String, notePath: String)

    @Query("DELETE FROM note_tags WHERE vaultId = :vaultId AND notePath = :notePath")
    suspend fun deleteTagsForNote(vaultId: String, notePath: String)

    @Query("DELETE FROM note_tasks WHERE vaultId = :vaultId AND notePath = :notePath")
    suspend fun deleteTasksForNote(vaultId: String, notePath: String)

    @Query("DELETE FROM note_properties WHERE vaultId = :vaultId AND notePath = :notePath")
    suspend fun deletePropertiesForNote(vaultId: String, notePath: String)

    @Query("DELETE FROM notes WHERE vaultId = :vaultId AND path = :path")
    suspend fun deleteNoteRow(vaultId: String, path: String)

    @Query("DELETE FROM note_fts WHERE rowid IN (SELECT ftsRowId FROM notes WHERE vaultId = :vaultId)")
    suspend fun deleteVaultFts(vaultId: String)

    @Query("DELETE FROM notes WHERE vaultId = :vaultId")
    suspend fun deleteVaultNotes(vaultId: String)

    @Transaction
    suspend fun replaceNoteIndex(
        note: NoteEntity,
        fts: NoteFtsEntity,
        links: List<NoteLinkEntity>,
        tags: List<NoteTagEntity>,
        tasks: List<NoteTaskEntity>,
        properties: List<NotePropertyEntity>,
    ) {
        val previousRowId = getFtsRowId(note.vaultId, note.path)
        if (previousRowId != null && previousRowId != 0L) {
            deleteFtsByRowId(previousRowId)
        }
        val newRowId = insertFts(fts)
        upsertNote(note.copy(ftsRowId = newRowId))

        deleteLinksForNote(note.vaultId, note.path)
        if (links.isNotEmpty()) insertLinks(links)

        deleteTagsForNote(note.vaultId, note.path)
        if (tags.isNotEmpty()) insertTags(tags)

        deleteTasksForNote(note.vaultId, note.path)
        if (tasks.isNotEmpty()) insertTasks(tasks)

        deletePropertiesForNote(note.vaultId, note.path)
        if (properties.isNotEmpty()) insertProperties(properties)
    }

    @Transaction
    suspend fun deleteNote(vaultId: String, path: String) {
        val ftsRowId = getFtsRowId(vaultId, path)
        if (ftsRowId != null && ftsRowId != 0L) {
            deleteFtsByRowId(ftsRowId)
        }
        deleteNoteRow(vaultId, path)
    }

    @Transaction
    suspend fun deleteVaultIndex(vaultId: String) {
        deleteVaultFts(vaultId)
        deleteVaultNotes(vaultId)
    }
}
