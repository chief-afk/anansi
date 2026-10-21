package com.chief.anansi.data.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Parses and validates an LNReader v1 backup before anything touches the
 * database. The shape dispatch is the producing format's own rule: a bare
 * JSON array is the official upstream shape (novels only), an object with a
 * `novels` array is the fork shape, and anything else is not a recognizable
 * LNReader backup file. Unknown keys are ignored; item-level failures are
 * isolated into skips so one bad novel never blocks the rest of the file.
 */
object LnReaderBackupParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): ParsedLnReaderBackup {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw notJson(e.message)
        } catch (e: IllegalArgumentException) {
            throw notJson(e.message)
        }
        return when (root) {
            is JsonArray -> parseOfficial(root)
            is JsonObject -> parseFork(root)
            else -> throw notRecognizable(
                "the file's top level is neither a JSON array nor a JSON object",
            )
        }
    }

    /** The official upstream shape: a bare JSON array of novels, nothing else. */
    private fun parseOfficial(root: JsonArray): ParsedLnReaderBackup {
        val novels = ArrayList<LnReaderBackupNovel>(root.size)
        val skipped = ArrayList<BackupItemSkip>()
        root.forEachIndexed { index, element ->
            val result = decodeItem<LnReaderBackupNovel>(element, "Novel ${index + 1}", "novelName")
            val value = result.value
            if (value != null) novels += value else skipped += requireNotNull(result.skip)
        }
        return ParsedLnReaderBackup(
            file = LnReaderBackupFile(novels = novels),
            skippedNovels = skipped,
            skippedCategories = 0,
            skippedChapters = emptyList(),
            skippedHistory = emptyList(),
        )
    }

    /** The fork shape: an object whose `novels` member is an array. */
    private fun parseFork(root: JsonObject): ParsedLnReaderBackup {
        val novelsElement = root["novels"]
        if (novelsElement !is JsonArray) {
            throw notRecognizable(
                "the top-level JSON object has no \"novels\" array " +
                    "(an LNReader backup is a JSON array of novels, the official v1 " +
                    "format, or an object with a \"novels\" array, the fork format)",
            )
        }
        val novels = ArrayList<LnReaderBackupNovel>(novelsElement.size)
        val skippedNovels = ArrayList<BackupItemSkip>()
        novelsElement.forEachIndexed { index, element ->
            val result = decodeItem<LnReaderBackupNovel>(element, "Novel ${index + 1}", "novelName")
            val value = result.value
            if (value != null) novels += value else skippedNovels += requireNotNull(result.skip)
        }

        val categories = ArrayList<LnReaderBackupCategory>()
        var categoriesSkipped = 0
        (root["categories"] as? JsonArray)?.forEachIndexed { index, element ->
            val result = decodeItem<LnReaderBackupCategory>(element, "Category ${index + 1}", "name")
            val value = result.value
            if (value != null) categories += value else categoriesSkipped++
        }

        val chapters = ArrayList<LnReaderBackupChapter>()
        val skippedChapters = ArrayList<BackupItemSkip>()
        (root["chapters"] as? JsonArray)?.forEachIndexed { index, element ->
            val result = decodeItem<LnReaderBackupChapter>(element, "Chapter ${index + 1}", "chapterName")
            val value = result.value
            if (value != null) chapters += value else skippedChapters += requireNotNull(result.skip)
        }

        val history = ArrayList<LnReaderBackupHistoryRow>()
        val skippedHistory = ArrayList<BackupItemSkip>()
        (root["history"] as? JsonArray)?.forEachIndexed { index, element ->
            val result = decodeItem<LnReaderBackupHistoryRow>(element, "History row ${index + 1}", null)
            val value = result.value
            if (value != null) history += value else skippedHistory += requireNotNull(result.skip)
        }

        return ParsedLnReaderBackup(
            file = LnReaderBackupFile(
                format = root.stringField("format"),
                version = root.intField("version"),
                exportedAt = root.stringField("exportedAt"),
                novels = novels,
                categories = categories,
                chapters = chapters,
                history = history,
            ),
            skippedNovels = skippedNovels,
            skippedCategories = categoriesSkipped,
            skippedChapters = skippedChapters,
            skippedHistory = skippedHistory,
        )
    }

    /** One item decode: the value, or the skip carrying the decode failure's reason. */
    private class ItemResult<T>(val value: T?, val skip: BackupItemSkip?)

    private inline fun <reified T> decodeItem(
        element: JsonElement,
        label: String,
        nameField: String?,
    ): ItemResult<T> = try {
        ItemResult(json.decodeFromJsonElement<T>(element), null)
    } catch (e: Exception) {
        val name = if (nameField == null || element !is JsonObject) {
            null
        } else {
            (element[nameField] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
        ItemResult(
            null,
            BackupItemSkip(
                name?.takeIf { it.isNotBlank() } ?: label,
                e.message ?: "invalid backup item",
            ),
        )
    }

    private fun JsonObject.stringField(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.intField(name: String): Int? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

    private fun notJson(detail: String?): LnReaderBackupFormatException =
        LnReaderBackupFormatException("The backup file is not valid JSON: ${detail ?: "parse failed"}")

    private fun notRecognizable(detail: String): LnReaderBackupFormatException =
        LnReaderBackupFormatException(
            "Not a recognizable LNReader backup file: $detail.",
        )
}
