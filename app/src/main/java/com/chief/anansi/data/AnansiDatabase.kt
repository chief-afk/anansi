package com.chief.anansi.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.chief.anansi.data.dao.ChapterDao
import com.chief.anansi.data.dao.NovelDao
import com.chief.anansi.data.dao.ReadingProgressDao
import com.chief.anansi.data.dao.SourceDao
import com.chief.anansi.data.entity.ChapterEntity
import com.chief.anansi.data.entity.NovelEntity
import com.chief.anansi.data.entity.ReadingProgressEntity
import com.chief.anansi.data.entity.SourceEntity

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
    ],
    version = 1,
    exportSchema = true,
)
abstract class AnansiDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun novelDao(): NovelDao
    abstract fun chapterDao(): ChapterDao
    abstract fun readingProgressDao(): ReadingProgressDao

    companion object {
        const val NAME = "anansi.db"
    }
}
