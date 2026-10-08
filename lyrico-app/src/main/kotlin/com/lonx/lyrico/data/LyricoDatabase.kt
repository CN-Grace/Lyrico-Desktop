package com.lonx.lyrico.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lonx.lyrico.data.model.dao.AppLogDao
import com.lonx.lyrico.data.model.dao.BatchTaskDao
import com.lonx.lyrico.data.model.dao.FolderDao
import com.lonx.lyrico.data.model.dao.LibraryIndexDao
import com.lonx.lyrico.data.model.dao.SongCustomTagKeyDao
import com.lonx.lyrico.data.model.dao.SongDao
import com.lonx.lyrico.data.model.dao.SourcePluginDao
import com.lonx.lyrico.data.model.entity.AlbumEntity
import com.lonx.lyrico.data.model.entity.AlbumSongCrossRef
import com.lonx.lyrico.data.model.entity.AppLogEntity
import com.lonx.lyrico.data.model.entity.ArtistEntity
import com.lonx.lyrico.data.model.entity.ArtistSongCrossRef
import com.lonx.lyrico.data.model.entity.BatchTaskEntity
import com.lonx.lyrico.data.model.entity.BatchTaskItemEntity
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongCustomTagKeyEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.entity.SourcePluginEntity
import java.io.File

/**
 * The library database, ported from the Android app. The entity set and column definitions are the
 * same as the Android schema version 21 — verified by a field-by-field diff of the generated
 * `schemas/com.lonx.lyrico.data.LyricoDatabase/1.json` against the inherited
 * `schemas-android/com.lonx.lyrico.data.LyricoDatabase/21.json`: 0 differences, same Room
 * `identity_hash`, so an existing Android library opens here without a migration and a copied
 * database file "just works".
 *
 * The schema version restarts at 1 because the Android migration history (versions 1..21, with the
 * uri/SAF and batch-match eras) describes databases that never existed on Windows. Fresh desktop
 * migrations are added normally from version 1 onwards. Schema layout:
 * - `lyrico-app/schemas/` — this database's history, starting at 1 (written by KSP).
 * - `lyrico-app/schemas-android/` — the inherited Android history, read-only reference.
 *
 * Android specifics that are gone: no `Context` (see [openLyricoDatabase]) and no
 * `AutoMigration`/`Migration` list.
 */
@Database(
    entities = [
        SongEntity::class,
        FolderEntity::class,
        BatchTaskEntity::class,
        BatchTaskItemEntity::class,
        AppLogEntity::class,
        ArtistEntity::class,
        ArtistSongCrossRef::class,
        AlbumEntity::class,
        AlbumSongCrossRef::class,
        SourcePluginEntity::class,
        SongCustomTagKeyEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LyricoDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun folderDao(): FolderDao
    abstract fun batchTaskDao(): BatchTaskDao
    abstract fun appLogDao(): AppLogDao
    abstract fun libraryIndexDao(): LibraryIndexDao
    abstract fun sourcePluginDao(): SourcePluginDao
    abstract fun songCustomTagKeyDao(): SongCustomTagKeyDao

    companion object {
        /** Database file name inside the app data directory. */
        const val DATABASE_FILE_NAME = "lyrico.db"

        private val CREATE_LYRIC_FTS_TABLE_SQL = """
            CREATE VIRTUAL TABLE IF NOT EXISTS song_lyric_lines_fts
            USING fts4(
                songUri,
                lineIndex,
                lineText,
                indexedText,
                notindexed=songUri,
                notindexed=lineIndex,
                notindexed=lineText,
                tokenize=unicode61
            )
        """.trimIndent()

        /**
         * Creates the full-text index table used by local lyric search.
         *
         * Room cannot declare virtual tables, so the table is created outside the schema — on both
         * database creation and every open, exactly like the Android original did. The bundled
         * SQLite (3.50.1) ships with FTS4 and the `unicode61` tokenizer the CJK index relies on.
         */
        fun createLyricFtsTable(connection: SQLiteConnection) {
            connection.prepare(CREATE_LYRIC_FTS_TABLE_SQL).use { statement -> statement.step() }
        }
    }
}

/**
 * Creates the FTS table as soon as the database is usable. `IF NOT EXISTS` makes this safe to run on
 * every open; the driver is the bundled one, so a missing FTS module would fail loudly here rather
 * than silently degrade lyric search.
 */
private object LyricFtsCallback : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) {
        LyricoDatabase.createLyricFtsTable(connection)
    }

    override fun onOpen(connection: SQLiteConnection) {
        LyricoDatabase.createLyricFtsTable(connection)
    }
}

/**
 * Opens (creating on first run) the library database under [dataDir].
 *
 * SQLite comes from the bundled driver, so the app does not depend on a system `sqlite3.dll`.
 */
fun openLyricoDatabase(dataDir: File): LyricoDatabase {
    dataDir.mkdirs()
    val databaseFile = File(dataDir, LyricoDatabase.DATABASE_FILE_NAME)
    return Room.databaseBuilder<LyricoDatabase>(name = databaseFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .addCallback(LyricFtsCallback)
        .build()
}
