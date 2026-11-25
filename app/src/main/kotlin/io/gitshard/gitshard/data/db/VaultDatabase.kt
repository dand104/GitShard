package io.gitshard.gitshard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.gitshard.gitshard.data.db.dao.GraphDao
import io.gitshard.gitshard.data.db.dao.NoteDao
import io.gitshard.gitshard.data.db.dao.TaskDao
import io.gitshard.gitshard.data.db.entity.NoteEntity
import io.gitshard.gitshard.data.db.entity.NoteFtsEntity
import io.gitshard.gitshard.data.db.entity.NoteLinkEntity
import io.gitshard.gitshard.data.db.entity.NotePropertyEntity
import io.gitshard.gitshard.data.db.entity.NoteTagEntity
import io.gitshard.gitshard.data.db.entity.NoteTaskEntity

@Database(
    entities = [
        NoteEntity::class,
        NoteFtsEntity::class,
        NoteLinkEntity::class,
        NoteTagEntity::class,
        NoteTaskEntity::class,
        NotePropertyEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun graphDao(): GraphDao
    abstract fun taskDao(): TaskDao

    companion object {
        const val DATABASE_NAME = "gitshard_index.db"

        fun create(context: Context): VaultDatabase =
            Room.databaseBuilder(context.applicationContext, VaultDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
