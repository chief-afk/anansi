package com.chief.anansi.plugin.local

import kotlinx.serialization.Serializable

/**
 * The per-novel manifest written at import into the novel's stored directory
 * (`book.json`): the metadata the details screen shows and the chapter list
 * in extraction order. Chapter text is not stored here; [LocalSource] serves
 * it from the stored original (`original.epub` / `original.txt`) by walking
 * the same extraction the import ran, so the stored copy stays the single
 * source of truth.
 */
@Serializable
data class LocalBookManifest(
    /** "epub" or "txt": which extraction serves the chapter text. */
    val format: String,
    val originalFileName: String,
    val title: String,
    val author: String? = null,
    val summary: String? = null,
    val chapters: List<Chapter> = emptyList(),
) {

    @Serializable
    data class Chapter(
        val title: String,

        /**
         * The chapter's stable path inside the Source seam: the resolved zip
         * entry href for EPUB, the split index for TXT.
         */
        val path: String,
    )

    companion object {
        const val FORMAT_EPUB = "epub"
        const val FORMAT_TXT = "txt"
        const val FILE_NAME = "book.json"
    }
}
