package com.chief.anansi.plugin.local

import com.chief.anansi.data.repo.SourceEcosystem
import com.chief.anansi.plugin.Source
import com.chief.anansi.plugin.SourceChapterItem
import com.chief.anansi.plugin.SourceNovel
import com.chief.anansi.plugin.SourceNovelItem
import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourceErrorRegistry
import com.chief.anansi.plugin.error.SourcePluginException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement

/**
 * The Local pseudo-source behind the novel-native [Source] seam:
 * one fixed zero-runtime source whose novels are the files
 * imported into private storage. Details and the chapter list come from the
 * import-time manifest; chapter text is re-extracted from the stored copy,
 * never from the (long-gone) picker URI. Failures are produced once here at
 * the adapter boundary into the per-source registry and thrown as loud
 * [SourcePluginException]s(the shared error taxonomy).
 */
class LocalSource(
    private val library: LocalLibrary,
    private val registry: SourceErrorRegistry,

    /** The registered source row's id; imported novels key on it. */
    override val id: Long,
    override val name: String,
    override val lang: String,
) : Source {

    override val pluginId: String = LocalLibrary.SOURCE_ID
    override val iconUrl: String? = null
    override val ecosystem: String = SourceEcosystem.LOCAL.key
    override val generation: String = ""
    override val filters: JsonElement? = null
    override val settings: JsonElement? = null

    /**
     * The imported-novels listing: page 1 is every imported novel, later
     * pages are empty (local has no pagination).
     */
    override suspend fun popularNovels(
        page: Int,
        showLatestNovels: Boolean,
        filterValuesJson: String?,
    ): List<SourceNovelItem> = guarded {
        if (page > 1) {
            emptyList()
        } else {
            library.importedNovels(id).map { row ->
                SourceNovelItem(
                    name = row.novel.title,
                    path = row.novel.url,
                    cover = row.novel.coverUrl,
                )
            }
        }
    }

    /** The imported novels whose title contains the query, case-insensitive. */
    override suspend fun searchNovels(
        query: String,
        page: Int,
        filterValuesJson: String?,
    ): List<SourceNovelItem> = guarded {
        if (page > 1) {
            emptyList()
        } else {
            library.importedNovels(id)
                .filter { it.novel.title.contains(query, ignoreCase = true) }
                .map { row ->
                    SourceNovelItem(
                        name = row.novel.title,
                        path = row.novel.url,
                        cover = row.novel.coverUrl,
                    )
                }
        }
    }

    /** Manifest-backed details: metadata and the chapter list in extraction order. */
    override suspend fun novelDetails(path: String): SourceNovel = guarded {
        val row = library.novelByPath(id, path)
            ?: throw parseFailure("No imported novel \"$path\" exists in this library")
        val manifest = library.manifestOf(row.id)
        SourceNovel(
            path = path,
            name = manifest.title,
            cover = row.coverUrl,
            genres = null,
            summary = manifest.summary,
            author = manifest.author,
            artist = null,
            status = null,
            chapters = manifest.chapters.map { chapter ->
                SourceChapterItem(name = chapter.title, path = chapter.path)
            },
        )
    }

    /** Chapter text re-extracted from the stored copy. */
    override suspend fun chapterText(novelPath: String, chapterPath: String): String = guarded {
        val row = library.novelByPath(id, novelPath)
            ?: throw parseFailure("No imported novel \"$novelPath\" exists in this library")
        val manifest = library.manifestOf(row.id)
        if (manifest.chapters.none { it.path == chapterPath }) {
            throw parseFailure(
                "The stored novel \"${manifest.title}\" has no chapter at \"$chapterPath\"",
            )
        }
        library.chapterText(row.id, manifest, chapterPath)
    }

    /** The registry boundary: success clears, failure reports, both once. */
    private suspend fun <T> guarded(block: suspend () -> T): T =
        try {
            block().also { registry.clear(pluginId) }
        } catch (e: SourcePluginException) {
            registry.report(pluginId, e.error)
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = SourceError.Parse("Local library \"$name\" failed: ${e.message}")
            registry.report(pluginId, error)
            throw SourcePluginException(error, e)
        }

    private fun parseFailure(reason: String): SourcePluginException =
        SourcePluginException(SourceError.Parse(reason), null)
}
