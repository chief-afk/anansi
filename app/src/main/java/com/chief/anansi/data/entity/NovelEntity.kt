package com.chief.anansi.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A novel known from a registered source. `sourceNovelId` is the source-local
 * id and is unique together with the source. Webnovels-only by decision, so
 * there is no content-kind column; a future second kind would be a new
 * migration, not a mangled table.
 */
@Entity(
    tableName = "novels",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourceId", "sourceNovelId"], unique = true),
    ],
)
data class NovelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val sourceNovelId: String,
    val title: String,
    val author: String?,
    val artist: String?,
    val status: String?,
    val genre: String?,
    val summary: String?,
    val coverUrl: String?,
    val url: String,
    val inLibrary: Boolean,
    val addedAt: Long,
    // When this novel's chapters were last checked against its source
    // (schema v3, the manual update-check phase); 0 means never checked.
    // The declared default matches the migration's ADD COLUMN literal.
    @ColumnInfo(defaultValue = "0") val lastCheckedAtEpochMs: Long = 0,
)
