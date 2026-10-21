package com.chief.anansi.plugin

import kotlinx.serialization.json.JsonElement

/**
 * A novel content source: the ecosystem-agnostic contract every adapter
 * implements (one novel-native seam, not one per ecosystem). Concrete
 * adapters arrive with the LNReader v3/v1-era phases; the interface exists
 * from the start so no later phase redefines it.
 *
 * Filters and settings pass through as the plugin's own JSON schema; user
 * values ride in the plugin's storage scope, never in translated Kotlin
 * models. [chapterText] takes both the novel and chapter paths because the
 * v1-era contract needs both while the v3 contract ignores the novel path.
 */
interface Source {
    val id: Long
    val pluginId: String
    val name: String
    val lang: String
    val iconUrl: String?

    /** Ecosystem label, e.g. "lnreader". */
    val ecosystem: String

    /** Plugin-generation label inside the ecosystem, e.g. "v3". */
    val generation: String

    /** The source's filter schema, passthrough, or null when it has none. */
    val filters: JsonElement?

    /** The source's settings schema, passthrough, or null when it has none. */
    val settings: JsonElement?

    suspend fun popularNovels(
        page: Int,
        showLatestNovels: Boolean,
        filterValuesJson: String?,
    ): List<SourceNovelItem>

    suspend fun searchNovels(
        query: String,
        page: Int,
        filterValuesJson: String?,
    ): List<SourceNovelItem>

    /** Novel metadata plus the chapter list. */
    suspend fun novelDetails(path: String): SourceNovel

    suspend fun chapterText(novelPath: String, chapterPath: String): String
}

data class SourceNovelItem(
    val name: String,
    val path: String,
    val cover: String? = null,
)

data class SourceChapterItem(
    val name: String,
    val path: String,
    val chapterNumber: Double? = null,
    val releaseTime: String? = null,
    val scanlator: String? = null,
    val page: String? = null,
)

data class SourceNovel(
    val path: String,
    val name: String? = null,
    val cover: String? = null,
    val genres: String? = null,
    val summary: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: String? = null,
    val totalPages: Int? = null,
    val chapters: List<SourceChapterItem> = emptyList(),
)
