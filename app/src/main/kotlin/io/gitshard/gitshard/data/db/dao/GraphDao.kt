package io.gitshard.gitshard.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import io.gitshard.gitshard.data.db.entity.NoteEntity
import io.gitshard.gitshard.data.db.entity.NoteLinkEntity
import kotlinx.coroutines.flow.Flow

data class BacklinkItem(
    val sourcePath: String,
    val sourceTitle: String,
    val sourceSnippet: String,
    val rawTarget: String,
    val subpath: String?,
    val alias: String?,
    val isEmbed: Boolean,
    val lineNumber: Int,
)

data class BrokenLinkItem(
    val sourcePath: String,
    val sourceTitle: String,
    val rawTarget: String,
    val targetNote: String,
    val lineNumber: Int,
)

data class TagCount(
    val tag: String,
    val noteCount: Int,
)

@Dao
interface GraphDao {

    @Query(
        """
        SELECT l.sourcePath, n.title AS sourceTitle, n.snippet AS sourceSnippet,
               l.rawTarget, l.subpath, l.alias, l.isEmbed, l.lineNumber
        FROM note_links l
        INNER JOIN notes n ON l.vaultId = n.vaultId AND l.sourcePath = n.path
        WHERE l.vaultId = :vaultId
          AND l.resolvedTargetPath = :targetPath
          AND l.sourcePath != :targetPath
        ORDER BY n.title COLLATE NOCASE ASC, l.lineNumber ASC
        """,
    )
    fun observeBacklinks(vaultId: String, targetPath: String): Flow<List<BacklinkItem>>

    @Query(
        """
        SELECT * FROM note_links
        WHERE vaultId = :vaultId AND sourcePath = :sourcePath
        ORDER BY lineNumber ASC, id ASC
        """,
    )
    fun observeOutgoingLinks(vaultId: String, sourcePath: String): Flow<List<NoteLinkEntity>>

    @Query(
        """
        SELECT l.sourcePath, n.title AS sourceTitle, l.rawTarget, l.targetNote, l.lineNumber
        FROM note_links l
        INNER JOIN notes n ON l.vaultId = n.vaultId AND l.sourcePath = n.path
        WHERE l.vaultId = :vaultId AND l.resolvedTargetPath IS NULL
        ORDER BY l.targetNote COLLATE NOCASE ASC, l.sourcePath ASC, l.lineNumber ASC
        """,
    )
    fun observeBrokenLinks(vaultId: String): Flow<List<BrokenLinkItem>>

    @Query(
        """
        SELECT tag, COUNT(*) AS noteCount
        FROM note_tags
        WHERE vaultId = :vaultId
        GROUP BY tag
        ORDER BY noteCount DESC, tag ASC
        """,
    )
    fun observeTagCounts(vaultId: String): Flow<List<TagCount>>

    @Query(
        """
        SELECT DISTINCT n.* FROM notes n
        INNER JOIN note_tags t ON n.vaultId = t.vaultId AND n.path = t.notePath
        WHERE n.vaultId = :vaultId
          AND (t.tag = :tag OR (LENGTH(t.tag) > LENGTH(:tag) AND SUBSTR(t.tag, 1, LENGTH(:tag) + 1) = :tag || '/'))
        ORDER BY n.lastModified DESC
        """,
    )
    fun observeNotesByTag(vaultId: String, tag: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM note_links WHERE vaultId = :vaultId")
    suspend fun getAllLinks(vaultId: String): List<NoteLinkEntity>

    @Query("UPDATE note_links SET resolvedTargetPath = :resolvedTargetPath WHERE id = :linkId")
    suspend fun updateResolvedTarget(linkId: Long, resolvedTargetPath: String?)
}
