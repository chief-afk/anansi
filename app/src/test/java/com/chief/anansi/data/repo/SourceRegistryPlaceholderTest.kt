package com.chief.anansi.data.repo

import com.chief.anansi.data.entity.SourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure conversion-helper tests: the needs-source placeholder a removal
 * produces carries exactly the minted shape `registerNeedsSource` creates
 * (disabled, repo-less, identity preserved), and prune's stale selection
 * takes precisely the rows whose plugin id the keep set no longer lists.
 */
class SourceRegistryPlaceholderTest {

    private fun registeredRow(sourceId: String = "731") = SourceEntity(
        id = 12,
        ecosystem = "lnreader",
        sourceId = sourceId,
        identity = "lnreader_$sourceId",
        numericId = 745,
        displayName = "VoidLibrary",
        generation = "v3",
        enabled = true,
        pinned = true,
        repoUrl = "https://repo.example/lnrepo.json",
        distUrl = "https://repo.example/dist/v3/voidlibrary.js",
        lang = "en",
        iconUrl = "https://repo.example/logo.png",
    )

    @Test
    fun placeholderReproducesTheMintedNeedsSourceShape() {
        val row = registeredRow()
        val placeholder = needsSourcePlaceholder(row)

        // The registration columns default to the exact shape
        // registerNeedsSource's mintAndInsert call produces.
        val minted = SourceEntity(
            ecosystem = row.ecosystem,
            sourceId = row.sourceId,
            identity = row.identity,
            numericId = row.numericId,
            displayName = row.displayName,
            enabled = false,
        )
        assertEquals(minted.copy(id = row.id, pinned = row.pinned), placeholder)

        // Identity survives, so the novels stay attached and a repository
        // carrying the plugin id re-links them in place.
        assertEquals(row.id, placeholder.id)
        assertEquals(row.ecosystem, placeholder.ecosystem)
        assertEquals(row.sourceId, placeholder.sourceId)
        assertEquals(row.identity, placeholder.identity)
        assertEquals(row.numericId, placeholder.numericId)
        assertEquals(row.displayName, placeholder.displayName)
        assertEquals(row.pinned, placeholder.pinned)
        assertTrue(!placeholder.enabled)
        assertEquals("", placeholder.generation)
        assertEquals("", placeholder.repoUrl)
        assertEquals("", placeholder.distUrl)
        assertEquals("", placeholder.lang)
        assertEquals("", placeholder.iconUrl)
    }

    @Test
    fun staleSelectionTakesExactlyThePluginIdsAbsentFromTheKeepSet() {
        val kept = registeredRow(sourceId = "731")
        val stale = registeredRow(sourceId = "732")
        val selected = staleRepoSources(
            rows = listOf(kept, stale),
            keepSourceIds = setOf("731"),
        )
        assertEquals(listOf(stale), selected)
        assertEquals(emptyList<SourceEntity>(), staleRepoSources(listOf(kept), setOf("731")))
        assertEquals(listOf(kept, stale), staleRepoSources(listOf(kept, stale), emptySet()))
    }
}
