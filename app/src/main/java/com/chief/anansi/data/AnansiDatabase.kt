package com.chief.anansi.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.chief.anansi.data.dao.ChapterDao
import com.chief.anansi.data.dao.NovelDao
import com.chief.anansi.data.dao.PluginStorageDao
import com.chief.anansi.data.dao.ReadingProgressDao
import com.chief.anansi.data.dao.SourceDao
import com.chief.anansi.data.dao.SourceErrorDao
import com.chief.anansi.data.entity.ChapterEntity
import com.chief.anansi.data.entity.NovelEntity
import com.chief.anansi.data.entity.PluginStorageEntity
import com.chief.anansi.data.entity.ReadingProgressEntity
import com.chief.anansi.data.entity.SourceEntity
import com.chief.anansi.data.entity.SourceErrorEntity

/**
 * Anansi's persistent core. Schema version 1 is the committed baseline the
 * permanent backup-compatibility posture migrates forward from; the schema
 * JSON lives under `app/schemas/`.
 */
@Database(
    entities = [
        SourceEntity::class,
        NovelEntity::class,
        ChapterEntity::class,
        ReadingProgressEntity::class,
        SourceErrorEntity::class,
        PluginStorageEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AnansiDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun novelDao(): NovelDao
    abstract fun chapterDao(): ChapterDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun sourceErrorDao(): SourceErrorDao
    abstract fun pluginStorageDao(): PluginStorageDao

    companion object {
        const val NAME = "anansi.db"

        /** v2 (repo registration): source registration columns + the error registry table. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN generation TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sources ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE sources ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sources ADD COLUMN repoUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sources ADD COLUMN distUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sources ADD COLUMN lang TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sources ADD COLUMN iconUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sources_repoUrl` ON `sources` (`repoUrl`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `source_errors` (" +
                        "`sourceId` TEXT NOT NULL, `kind` TEXT NOT NULL, `reason` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`sourceId`))",
                )
            }
        }

        /** v3 (manual update checks): new-arrival state + check bookkeeping. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapters ADD COLUMN isNew INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "ALTER TABLE chapters ADD COLUMN firstSeenAtEpochMs INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE novels ADD COLUMN lastCheckedAtEpochMs INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * The plugin_storage create statement; the committed v4 export
         * schema declares the same SQL (pinned by PluginStorageMigrationTest).
         */
        const val PLUGIN_STORAGE_CREATE_SQL =
            "CREATE TABLE IF NOT EXISTS `plugin_storage` (" +
                "`pluginId` TEXT NOT NULL, `document` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`pluginId`))"

        /** v4 (plugin storage durability): per-plugin storage documents. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(PLUGIN_STORAGE_CREATE_SQL)
            }
        }
    }
}
