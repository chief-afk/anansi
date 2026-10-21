package com.chief.anansi.ui.reader

/**
 * The session text cache: chapter text fetched this session stays in memory
 * so a failed retry keeps the already-fetched text visible and reopening a
 * chapter skips the refetch. No database cache (downloads are a backlog
 * row); entries die with the process.
 */
class ChapterTextCache(maxEntries: Int = 6) {

    private val entries = object : LinkedHashMap<Long, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(chapterRowId: Long): String? = entries[chapterRowId]

    @Synchronized
    fun put(chapterRowId: Long, text: String) {
        entries[chapterRowId] = text
    }
}
