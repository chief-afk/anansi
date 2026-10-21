package com.chief.anansi.plugin.local

import com.chief.anansi.data.AnansiDatabase
import com.chief.anansi.data.dao.SourceNovelRow
import com.chief.anansi.data.entity.ChapterEntity
import com.chief.anansi.data.entity.NovelEntity
import com.chief.anansi.data.entity.SourceEntity
import com.chief.anansi.data.repo.SourceEcosystem
import com.chief.anansi.data.repo.SourceRegistryRepository
import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourceErrorRegistry
import com.chief.anansi.plugin.error.SourcePluginException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The local import pipeline and stored-library (spec Requirements 1, 2, 5):
 * picked files stream into a staging directory under
 * `<filesDir>/local/incoming/<token>/` first; once the novel row is inserted
 * and its id exists, the staging directory renames to
 * `<filesDir>/local/<novelId>/` and the chapter rows are written. A failed
 * import deletes its staging directory and its rows, so nothing partial or
 * orphaned survives. The SAF URI is consumed at import only; every later
 * read goes through the stored copy.
 *
 * The fixed LOCAL pseudo-source registers idempotently at first import
 * through the same registry path every ecosystem uses
 * ([SourceRegistryRepository.registerSource]); its one identity is
 * [IDENTITY] (`local_1`).
 */
class LocalLibrary(
    database: AnansiDatabase,
    private val sourceRegistry: SourceRegistryRepository,
    private val errorRegistry: SourceErrorRegistry,
    /** The `local/` root under the app's private files directory. */
    private val root: File,
) {
    private val novelDao = database.novelDao()
    private val chapterDao = database.chapterDao()
    private val incoming: File = File(root, "incoming")
    private val json = Json { ignoreUnknownKeys = true }

    /** The fixed plugin-id string of the one Local pseudo-source. */
    val sourceId: String get() = SOURCE_ID

    fun novelDirectory(novelRowId: Long): File = File(root, novelRowId.toString())

    /**
     * Registers the fixed Local pseudo-source (idempotent; the minting and
     * identity path is the registry's own), so the ecosystem has exactly one
     * source row forever.
     */
    suspend fun ensureRegistered(): SourceEntity =
        sourceRegistry.findByIdentity(IDENTITY)
            ?: sourceRegistry.registerSource(
                ecosystem = SourceEcosystem.LOCAL,
                sourceId = SOURCE_ID,
                displayName = DISPLAY_NAME,
            )

    /**
     * Imports one picked document. [open] must return a fresh input stream
     * per call and is invoked on the IO dispatcher; a null stream (provider
     * failure) fails the import loudly. Throws [SourcePluginException] with
     * the registry's own wording; the failure names the file.
     */
    suspend fun import(fileName: String, mimeType: String?, open: () -> InputStream?) {
        try {
            importInternal(fileName.ifBlank { "import" }, mimeType, open)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourcePluginException) {
            errorRegistry.report(SOURCE_ID, e.error)
            throw e
        } catch (e: Exception) {
            val error = SourceError.Parse(
                "The file \"$fileName\" could not be imported: ${e.message ?: "I/O error"}",
            )
            errorRegistry.report(SOURCE_ID, error)
            throw SourcePluginException(error, e)
        }
    }

    /**
     * Removes an imported novel completely: the stored directory goes first,
     * then the row (its chapters, read marks, and progress cascade with it).
     */
    suspend fun removeImportedNovel(novelRowId: Long) {
        withContext(Dispatchers.IO) {
            novelDirectory(novelRowId).deleteRecursively()
        }
        novelDao.deleteById(novelRowId)
    }

    // --- reads served to LocalSource ---

    suspend fun importedNovels(sourceRowId: Long): List<SourceNovelRow> =
        novelDao.observeBySource(sourceRowId).first()

    suspend fun novelByPath(sourceRowId: Long, novelPath: String): NovelEntity? =
        novelDao.observeBySourceNovel(sourceRowId, novelPath).first()

    /**
     * The display label of a novel's stored format ("EPUB" or "TXT"), read
     * from which original copy the directory holds.
     */
    suspend fun formatOf(novelRowId: Long): String = withContext(Dispatchers.IO) {
        val dir = novelDirectory(novelRowId)
        if (File(dir, ORIGINAL_EPUB).isFile) "EPUB" else "TXT"
    }

    suspend fun manifestOf(novelRowId: Long): LocalBookManifest = withContext(Dispatchers.IO) {
        val file = File(novelDirectory(novelRowId), LocalBookManifest.FILE_NAME)
        if (!file.isFile) {
            throw manifestMissing()
        }
        try {
            json.decodeFromString<LocalBookManifest>(file.readText())
        } catch (e: Exception) {
            throw SourcePluginException(
                SourceError.Parse("The imported novel's manifest is unreadable: ${e.message}"),
                e,
            )
        }
    }

    /** One chapter's text from the stored copy (never the SAF URI). */
    suspend fun chapterText(
        novelRowId: Long,
        manifest: LocalBookManifest,
        chapterPath: String,
    ): String = withContext(Dispatchers.IO) {
        val original = originalFile(novelRowId, manifest.format)
        when (manifest.format) {
            LocalBookManifest.FORMAT_EPUB -> EpubExtractor.chapterText(original, chapterPath)

            LocalBookManifest.FORMAT_TXT -> TxtImporter.chapterText(
                file = original,
                index = chapterPath.toIntOrNull()
                    ?: throw SourcePluginException(
                        SourceError.Parse("The TXT chapter path \"$chapterPath\" is not a split index"),
                        null,
                    ),
                fallbackTitle = txtFallbackTitle(manifest.originalFileName),
            )

            else -> throw SourcePluginException(
                SourceError.Parse(
                    "The imported novel's format \"${manifest.format}\" cannot be read",
                ),
                null,
            )
        }
    }

    private fun originalFile(novelRowId: Long, format: String): File {
        val name = if (format == LocalBookManifest.FORMAT_EPUB) ORIGINAL_EPUB else ORIGINAL_TXT
        val file = File(novelDirectory(novelRowId), name)
        if (!file.isFile) {
            throw SourcePluginException(
                SourceError.Parse("The imported novel's stored copy ($name) is missing"),
                null,
            )
        }
        return file
    }

    private fun manifestMissing(): SourcePluginException =
        SourcePluginException(
            SourceError.Parse("The imported novel's manifest (${LocalBookManifest.FILE_NAME}) is missing"),
            null,
        )

    // --- import steps ---

    private enum class Format { EPUB, TXT }

    private data class PreparedBook(
        val manifest: LocalBookManifest,
        /** The extracted cover inside the staging directory, when one exists. */
        val cover: File?,
    )

    private suspend fun importInternal(fileName: String, mimeType: String?, open: () -> InputStream?) {
        val format = detectFormat(fileName, mimeType)
        val source = ensureRegistered()
        val token = UUID.randomUUID().toString()
        val staging = File(incoming, token)
        var rowId: Long? = null
        try {
            withContext(Dispatchers.IO) {
                staging.mkdirs()
                val stream = open() ?: throw SourcePluginException(
                    SourceError.Parse("The file \"$fileName\" could not be opened from its provider"),
                    null,
                )
                val storedName = if (format == Format.EPUB) ORIGINAL_EPUB else ORIGINAL_TXT
                val stored = File(staging, storedName)
                stream.use { input ->
                    stored.outputStream().use { output -> input.copyTo(output) }
                }
                val prepared = when (format) {
                    Format.EPUB -> prepareEpub(stored, fileName, staging)
                    Format.TXT -> prepareTxt(stored, fileName)
                }
                File(staging, LocalBookManifest.FILE_NAME)
                    .writeText(json.encodeToString(prepared.manifest))

                val now = System.currentTimeMillis()
                val id = novelDao.insertNovel(
                    NovelEntity(
                        sourceId = source.id,
                        sourceNovelId = token,
                        title = prepared.manifest.title,
                        author = prepared.manifest.author,
                        artist = null,
                        status = null,
                        genre = null,
                        summary = prepared.manifest.summary,
                        coverUrl = null,
                        url = token,
                        inLibrary = true,
                        addedAt = now,
                    ),
                )
                rowId = id
                // The novel row exists: staging graduates to the novel's
                // permanent directory, then the chapter rows land.
                if (!staging.renameTo(novelDirectory(id))) {
                    throw IOException("could not move the staged import into place")
                }
                chapterDao.upsertChapters(
                    prepared.manifest.chapters.mapIndexed { index, chapter ->
                        ChapterEntity(
                            novelId = id,
                            sourceChapterId = chapter.path,
                            chapterUrl = chapter.path,
                            title = chapter.title,
                            releaseDate = null,
                            sortOrder = index,
                            isNew = false,
                        )
                    },
                )
                prepared.cover?.let { cover ->
                    // Resolve against the renamed directory: the staged File
                    // still points into incoming/.
                    val storedCover = File(novelDirectory(id), cover.name)
                    novelDao.setCoverUrl(id, android.net.Uri.fromFile(storedCover).toString())
                }
            }
        } catch (e: CancellationException) {
            discard(staging, rowId)
            throw e
        } catch (e: Exception) {
            discard(staging, rowId)
            throw e
        }
    }

    /** No partial rows, no orphaned directories: staging and any rows die. */
    private suspend fun discard(staging: File, rowId: Long?) {
        withContext(Dispatchers.IO) {
            staging.deleteRecursively()
            rowId?.let { id -> novelDirectory(id).deleteRecursively() }
        }
        rowId?.let { id -> novelDao.deleteById(id) }
    }

    private fun prepareEpub(stored: File, fileName: String, staging: File): PreparedBook {
        val book = EpubExtractor.extract(stored, label = fileName)
        val cover = book.cover?.let { coverImage ->
            try {
                File(staging, "cover${coverExtension(coverImage.mediaType)}").apply {
                    writeBytes(coverImage.bytes)
                }
            } catch (e: Exception) {
                // Best-effort per spec: a failing cover never fails the import.
                null
            }
        }
        return PreparedBook(
            manifest = LocalBookManifest(
                format = LocalBookManifest.FORMAT_EPUB,
                originalFileName = fileName,
                title = book.title,
                author = book.author,
                summary = book.summary,
                chapters = book.chapters.map { chapter ->
                    LocalBookManifest.Chapter(title = chapter.title, path = chapter.href)
                },
            ),
            cover = cover,
        )
    }

    private fun prepareTxt(stored: File, fileName: String): PreparedBook {
        val text = TxtImporter.decode(stored)
        val title = txtFallbackTitle(fileName)
        val chapters = TxtImporter.split(text, title)
        return PreparedBook(
            manifest = LocalBookManifest(
                format = LocalBookManifest.FORMAT_TXT,
                originalFileName = fileName,
                title = title,
                author = null,
                summary = null,
                chapters = chapters.mapIndexed { index, chapter ->
                    LocalBookManifest.Chapter(title = chapter.title, path = index.toString())
                },
            ),
            cover = null,
        )
    }

    /**
     * Specific mime wins; a generic or missing one falls back to the file
     * extension; anything else fails loudly naming the type.
     */
    private fun detectFormat(fileName: String, mimeType: String?): Format =
        when (mimeType) {
            "application/epub+zip" -> Format.EPUB
            "text/plain" -> Format.TXT
            else -> when {
                fileName.endsWith(".epub", ignoreCase = true) -> Format.EPUB
                fileName.endsWith(".txt", ignoreCase = true) -> Format.TXT
                else -> throw SourcePluginException(
                    SourceError.UnsupportedFeature(
                        "The file \"$fileName\" (type ${mimeType ?: "unknown"}) is neither " +
                            "EPUB nor TXT; Anansi imports EPUB and TXT files only",
                    ),
                    null,
                )
            }
        }

    private fun txtFallbackTitle(fileName: String): String {
        val stem = fileName.substringBeforeLast('.', missingDelimiterValue = fileName).trim()
        return stem.ifEmpty { fileName.ifEmpty { "Imported text" } }
    }

    private fun coverExtension(mediaType: String): String = when (mediaType) {
        "image/jpeg", "image/jpg" -> ".jpg"
        "image/png" -> ".png"
        "image/gif" -> ".gif"
        "image/webp" -> ".webp"
        else -> ".img"
    }

    companion object {
        const val SOURCE_ID = "1"
        const val IDENTITY = "local_1"
        const val DISPLAY_NAME = "Local"
        const val ORIGINAL_EPUB = "original.epub"
        const val ORIGINAL_TXT = "original.txt"
    }
}
