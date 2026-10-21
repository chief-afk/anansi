package com.chief.anansi.plugin.local

import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser

/**
 * EPUB spine extraction at stdlib level:
 * `java.util.zip.ZipFile` opens the stored copy, `META-INF/container.xml`
 * names the OPF root file, and the OPF's metadata (dc:title, dc:creator,
 * dc:description), manifest, and spine produce one chapter per spine XHTML
 * document in spine order. Text extraction runs on the platform
 * `XmlPullParser`; no EPUB library is involved.
 *
 * EPUB files are untrusted input: archives declaring more
 * than [MAX_ENTRIES] entries or more than [MAX_TOTAL_BYTES] decompressed
 * bytes are rejected, every read is counted against the same cap so declared
 * sizes cannot lie their way past it, and entry names are only ever used as
 * read-only exact-match lookups - extraction never writes a path derived
 * from the archive.
 */
object EpubExtractor {

    const val MAX_ENTRIES = 512
    const val MAX_TOTAL_BYTES: Long = 256L * 1024 * 1024
    private const val CONTAINER_ENTRY = "META-INF/container.xml"

    data class EpubBook(
        val title: String,
        val author: String?,
        val summary: String?,
        /** One entry per spine document, in spine order. */
        val chapters: List<Chapter>,
        /** The declared cover image, when the manifest names one. */
        val cover: Cover?,
    ) {

        data class Chapter(val title: String, val href: String)

        data class Cover(val mediaType: String, val bytes: ByteArray)
    }

    /**
     * Extracts the whole book: metadata, spine chapter list, cover. [label]
     * names the file in failure reasons; it is the user's picked name at
     * import and defaults to the stored copy's name elsewhere.
     */
    fun extract(file: File, label: String = file.name): EpubBook = openArchive(file, label).use { zip ->
        checkArchiveBounds(zip)
        val budget = Budget()
        val opfPath = opfRootPath(zip, budget)
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = parseOpf(
            xml = String(
                readEntry(zip, zip.requireEntry(opfPath, "OPF root file"), budget),
                Charsets.UTF_8,
            ),
            opfDir = opfDir,
        )
        if (opf.title.isBlank()) {
            throw parseFailure(label, "the OPF metadata of \"$opfPath\" has no dc:title")
        }
        val chapters = opf.spine.mapIndexed { index, href ->
            EpubBook.Chapter(
                title = chapterTitle(zip, href, budget, index),
                href = href,
            )
        }
        if (chapters.isEmpty()) {
            throw parseFailure(label, "the spine of \"$opfPath\" lists no documents")
        }
        EpubBook(
            title = opf.title,
            author = opf.creator,
            summary = opf.description,
            chapters = chapters,
            cover = coverImage(zip, opf, opfDir, budget),
        )
    }

    /** One chapter's text, re-extracted from the stored copy. */
    fun chapterText(file: File, href: String): String = openArchive(file, file.name).use { zip ->
        checkArchiveBounds(zip)
        val entry = zip.requireEntry(href, "chapter document")
        val xhtml = String(readEntry(zip, entry, Budget()), Charsets.UTF_8)
        EpubXhtml.parse(xhtml, label = href).body.ifBlank {
            throw parseFailure(file.name, "the chapter document \"$href\" has no readable text")
        }
    }

    private fun openArchive(file: File, label: String): ZipFile = try {
        ZipFile(file)
    } catch (e: Exception) {
        throw parseFailure(label, "not a readable EPUB archive: ${e.message ?: "corrupt zip"}")
    }

    /** Archive-level bounds, checked before any entry is read. */
    private fun checkArchiveBounds(zip: ZipFile) {
        val entries = zip.entries().asSequence().toList()
        if (entries.size > MAX_ENTRIES) {
            throw SourcePluginException(
                SourceError.Parse(
                    "The EPUB declares ${entries.size} entries, past the " +
                        "$MAX_ENTRIES-entry import bound",
                ),
                null,
            )
        }
        var declaredTotal = 0L
        for (entry in entries) {
            if (entry.size > 0) declaredTotal += entry.size
        }
        if (declaredTotal > MAX_TOTAL_BYTES) {
            throw SourcePluginException(
                SourceError.Parse(
                    "The EPUB declares more than $MAX_TOTAL_BYTES decompressed bytes",
                ),
                null,
            )
        }
    }

    private fun ZipFile.requireEntry(name: String, what: String): ZipEntry =
        getEntry(name) ?: throw SourcePluginException(
            SourceError.Parse("The EPUB's $what \"$name\" is missing from the archive"),
            null,
        )

    /** container.xml's first rootfile full-path (the OPF root file). */
    private fun opfRootPath(zip: ZipFile, budget: Budget): String {
        val container = zip.requireEntry(CONTAINER_ENTRY, "container description")
        val xml = String(readEntry(zip, container, budget), Charsets.UTF_8)
        val fullPath = try {
            val parser = newParser(xml)
            var found: String? = null
            while (found == null) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.START_TAG ->
                        if (parser.name.equals("rootfile", ignoreCase = true)) {
                            found = parser.getAttributeValue(null, "full-path")
                                ?.takeIf { it.isNotBlank() }
                        }
                }
            }
            found
        } catch (e: Exception) {
            throw SourcePluginException(
                SourceError.Parse(
                    "The EPUB's \"$CONTAINER_ENTRY\" is unparseable: ${e.message ?: "malformed XML"}",
                ),
                e,
            )
        }
        return fullPath ?: throw SourcePluginException(
            SourceError.Parse("The EPUB's \"$CONTAINER_ENTRY\" names no OPF root file"),
            null,
        )
    }

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
    )

    private data class Opf(
        val title: String,
        val creator: String?,
        val description: String?,
        val items: Map<String, ManifestItem>,
        /** Resolved zip-root-relative hrefs, in spine order. */
        val spine: List<String>,
        /** EPUB 2's `<meta name="cover" content="itemId">` value, when present. */
        val coverMetaId: String?,
    )

    private fun parseOpf(xml: String, opfDir: String): Opf = try {
        val parser = newParser(xml)
        var title = ""
        var creator: String? = null
        var description: String? = null
        var coverMetaId: String? = null
        val items = HashMap<String, ManifestItem>()
        val spine = ArrayList<String>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name.lowercase()) {
                "title" -> if (title.isEmpty()) title = parser.nextText().trim()
                "creator" -> creator = creator ?: parser.nextText().trim().ifEmpty { null }
                "description" -> description = description
                    ?: parser.nextText().trim().ifEmpty { null }

                "item" -> {
                    val id = parser.getAttributeValue(null, "id").orEmpty()
                    val href = parser.getAttributeValue(null, "href").orEmpty()
                    if (id.isNotEmpty() && href.isNotEmpty()) {
                        items[id] = ManifestItem(
                            id = id,
                            href = href,
                            mediaType = parser.getAttributeValue(null, "media-type").orEmpty(),
                            properties = parser.getAttributeValue(null, "properties").orEmpty(),
                        )
                    }
                }

                // EPUB 2 cover convention: <meta name="cover" content="<itemId>"/>
                "meta" -> if (parser.getAttributeValue(null, "name") == "cover") {
                    coverMetaId = parser.getAttributeValue(null, "content")
                }

                "itemref" -> {
                    val item = items[parser.getAttributeValue(null, "idref").orEmpty()]
                    if (item != null) {
                        spine += resolveHref(opfDir, item.href)
                    }
                }
            }
        }
        Opf(title, creator, description, items, spine, coverMetaId)
    } catch (e: Exception) {
        throw SourcePluginException(
            SourceError.Parse("The OPF root file is unparseable: ${e.message ?: "malformed XML"}"),
            e,
        )
    }

    /**
     * The chapter title for one spine document: the
     * document's first h1-h3 heading, else its title element, else
     * "Chapter N" by spine position.
     */
    private fun chapterTitle(zip: ZipFile, href: String, budget: Budget, position: Int): String {
        val parsed = EpubXhtml.parse(
            String(readEntry(zip, zip.requireEntry(href, "chapter document"), budget), Charsets.UTF_8),
            label = href,
        )
        return parsed.heading
            ?: parsed.title?.takeIf { it.isNotBlank() }
            ?: "Chapter ${position + 1}"
    }

    /** The cover image, best-effort: any trouble here means no cover, not a failure. */
    private fun coverImage(zip: ZipFile, opf: Opf, opfDir: String, budget: Budget): EpubBook.Cover? {
        val coverItem = opf.items.values.firstOrNull { item ->
            item.mediaType.startsWith("image/") && item.properties.split(' ').contains("cover-image")
        } ?: opf.coverMetaId?.let { metaId ->
            opf.items[metaId]?.takeIf { it.mediaType.startsWith("image/") }
        } ?: return null
        val entry = zip.getEntry(resolveHref(opfDir, coverItem.href)) ?: return null
        return try {
            EpubBook.Cover(mediaType = coverItem.mediaType, bytes = readEntry(zip, entry, budget))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Bounds-enforcing entry read: archive-level limits are checked up front,
     * then every byte actually decompressed counts against the same cap so a
     * lying declared size cannot exhaust storage.
     */
    private fun readEntry(zip: ZipFile, entry: ZipEntry, budget: Budget): ByteArray {
        val declared = entry.size
        if (declared >= 0 && budget.consumed + declared > MAX_TOTAL_BYTES) {
            throw boundsFailure(entry.name)
        }
        try {
            zip.getInputStream(entry).use { input ->
                val initialCapacity = declared.coerceIn(0, 1L shl 20).toInt()
                val output = ByteArrayOutputStream(initialCapacity)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    budget.consumed += read
                    if (budget.consumed > MAX_TOTAL_BYTES) {
                        throw boundsFailure(entry.name)
                    }
                    output.write(buffer, 0, read)
                }
                return output.toByteArray()
            }
        } catch (e: SourcePluginException) {
            throw e
        } catch (e: Exception) {
            throw SourcePluginException(
                SourceError.Parse(
                    "The EPUB entry \"${entry.name}\" could not be read: ${e.message ?: "I/O error"}",
                ),
                e,
            )
        }
    }

    private fun boundsFailure(name: String): SourcePluginException =
        SourcePluginException(
            SourceError.Parse(
                "The EPUB is too large to import: entry \"$name\" pushes it past the " +
                    "$MAX_TOTAL_BYTES-byte extraction bound",
            ),
            null,
        )

    /**
     * Resolves a manifest href against the OPF's directory into a
     * zip-root-relative entry name, normalizing `.` and `..` segments.
     */
    internal fun resolveHref(opfDir: String, href: String): String {
        if (href.isEmpty()) return href
        val combined = when {
            href.startsWith("/") -> href
            opfDir.isEmpty() -> href
            else -> "$opfDir/$href"
        }
        val segments = ArrayDeque<String>()
        for (segment in combined.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> segments.removeLastOrNull()
                else -> segments.addLast(segment)
            }
        }
        return segments.joinToString("/")
    }

    private fun newParser(xml: String): XmlPullParser = LocalXml.newParser(xml)

    private fun parseFailure(label: String, detail: String): SourcePluginException =
        SourcePluginException(
            SourceError.Parse("The EPUB \"$label\" could not be imported: $detail"),
            null,
        )

    /** The per-extract budget of bytes actually decompressed. */
    private class Budget {
        var consumed: Long = 0
    }
}
