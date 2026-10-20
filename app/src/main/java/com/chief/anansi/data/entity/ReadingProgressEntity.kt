package com.chief.anansi.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Reading progress: exactly one row per novel, keyed by the novel foreign key.
 * `percent` is the stored display value and 100 percent means finished.
 */
@Entity(
    tableName = "reading_progress",
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chapterId"]),
    ],
)
data class ReadingProgressEntity(
    @PrimaryKey val novelId: Long,
    val chapterId: Long,
    val scrollOffset: Long,
    val percent: Int,
    val updatedAt: Long,
)
