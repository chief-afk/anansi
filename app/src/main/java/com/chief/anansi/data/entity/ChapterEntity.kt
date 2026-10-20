package com.chief.anansi.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One chapter of a novel. `chapterUrl` holds the source-provided chapter URL
 * or path verbatim and is unique together with the novel: update diffs and
 * backup import key on it. Read marks are a column here; unread counts are
 * DAO aggregates, not stored counters.
 */
@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // Leftmost-prefix index on novelId, unique per (novel, chapterUrl).
        Index(value = ["novelId", "chapterUrl"], unique = true),
    ],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val novelId: Long,
    val sourceChapterId: String,
    val chapterUrl: String,
    val title: String,
    val releaseDate: String?,
    val sortOrder: Int,
    val isRead: Boolean = false,
)
