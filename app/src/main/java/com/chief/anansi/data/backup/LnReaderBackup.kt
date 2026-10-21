package com.chief.anansi.data.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The LNReader v1 backup format, mirrored field-name for field-name (the
 * `lnreader_backup_*.json` restore surface is permanent; it must keep
 * restoring in every release). This DTO family is the first concrete
 * artifact of the versioned-JSON direction: human-inspectable,
 * diffable, kotlinx-serialization only.
 *
 * Two shapes exist. The official upstream backup is a bare JSON array of
 * [LnReaderBackupNovel]. The fork backup is an [LnReaderBackupFile] object:
 * format, version, exportedAt, novels, categories, chapters, history.
 */
@Serializable
data class LnReaderBackupFile(
    val format: String? = null,
    val version: Int? = null,
    @SerialName("exportedAt") val exportedAt: String? = null,
    val novels: List<LnReaderBackupNovel> = emptyList(),
    val categories: List<LnReaderBackupCategory> = emptyList(),
    val chapters: List<LnReaderBackupChapter> = emptyList(),
    val history: List<LnReaderBackupHistoryRow> = emptyList(),
)

@Serializable
data class LnReaderBackupNovel(
    @SerialName("novelId") val novelId: Long,
    @SerialName("novelUrl") val novelUrl: String,
    @SerialName("sourceUrl") val sourceUrl: String,
    @SerialName("sourceId") val sourceId: Long,
    @SerialName("source") val source: String,
    @SerialName("novelName") val novelName: String,
    @SerialName("novelCover") val novelCover: String? = null,
    @SerialName("novelSummary") val novelSummary: String? = null,
    @SerialName("author") val author: String? = null,
    @SerialName("artist") val artist: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("genre") val genre: String? = null,
    @SerialName("followed") val followed: Int? = null,
    @SerialName("unread") val unread: Int? = null,
    @SerialName("categoryIds") val categoryIds: String? = null,
)

@Serializable
data class LnReaderBackupChapter(
    @SerialName("chapterId") val chapterId: Long,
    @SerialName("chapterUrl") val chapterUrl: String,
    @SerialName("novelId") val novelId: Long,
    @SerialName("chapterName") val chapterName: String = "",
    @SerialName("releaseDate") val releaseDate: String? = null,
    @SerialName("bookmark") val bookmark: Int? = null,
    @SerialName("read") val read: Int? = null,
    @SerialName("downloaded") val downloaded: Int? = null,
)

@Serializable
data class LnReaderBackupHistoryRow(
    @SerialName("historyNovelId") val historyNovelId: Long,
    @SerialName("historyChapterId") val historyChapterId: Long,
    @SerialName("historyTimeRead") val historyTimeRead: String,
)

@Serializable
data class LnReaderBackupCategory(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String,
    @SerialName("sort") val sort: Long? = null,
)

/**
 * One item of the backup that failed validation while the rest of the file
 * stayed usable; the import skips it and reports it, never silently.
 */
data class BackupItemSkip(val label: String, val reason: String)

/**
 * A fully parsed backup: the decoded envelope plus the items that decoded
 * cleanly, and the per-item skips. A file that reached this state passed
 * the top-level shape dispatch; item-level failures are skips, not errors.
 */
data class ParsedLnReaderBackup(
    val file: LnReaderBackupFile,
    val skippedNovels: List<BackupItemSkip>,
    val skippedCategories: Int,
    val skippedChapters: List<BackupItemSkip>,
    val skippedHistory: List<BackupItemSkip>,
)

/**
 * A top-level backup failure: invalid JSON, a truncated file, or a root
 * that is neither the official bare-array shape nor the fork object shape.
 * Nothing is written and the reason is shown verbatim.
 */
class LnReaderBackupFormatException(reason: String) : Exception(reason)
