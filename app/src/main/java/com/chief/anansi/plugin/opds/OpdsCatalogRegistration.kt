package com.chief.anansi.plugin.opds

import com.chief.anansi.data.repo.SourceEcosystem
import com.chief.anansi.data.repo.SourceRegistryRepository
import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import com.chief.anansi.plugin.repo.RepoUrls
import okhttp3.OkHttpClient

/**
 * The add-catalog flow: validate the URL through the
 * shared repository URL gate, fetch it, verify the root parses as an OPDS
 * 1.x feed, and register it through the source registration path
 * (`SourceRegistryRepository.upsertRepoSources`, the same minting and
 * upsert path plugins use; no second registration mechanism). The catalog
 * URL is stored as the source's `repoUrl` and `distUrl`, so the same
 * per-source remove path the management screen uses applies unchanged, and
 * re-adding the same URL is an in-place update of the one existing source.
 */
class OpdsCatalogRegistration(
    client: OkHttpClient,
    private val sourceRegistry: SourceRegistryRepository,
) {
    private val http = OpdsHttp(client)

    /** The outcome of one add attempt, for the flow's verified line. */
    data class CatalogAdded(
        val catalogUrl: String,
        val title: String,
        val created: Boolean,
    )

    suspend fun addCatalog(rawUrl: String): CatalogAdded {
        val catalogUrl = RepoUrls.validate(rawUrl)
        val response = http.fetchText(catalogUrl, "OPDS catalog")
        // Root-feed verification: this parses as an OPDS 1.x feed, or the
        // failure is loud with the reason (non-XML, JSON/OPDS 2, malformed).
        val feed = OpdsFeedParser.parse(response.text, catalogUrl)
        if (feed.title.isBlank()) {
            throw SourcePluginException(
                SourceError.Parse("The catalog feed at $catalogUrl has no title"),
                null,
            )
        }
        val outcome = sourceRegistry.upsertRepoSources(
            repoUrl = catalogUrl,
            sources = listOf(
                SourceRegistryRepository.RepoSourceUpsert(
                    ecosystem = SourceEcosystem.OPDS.key,
                    sourceId = OpdsCatalogIdentity.sourceIdFor(catalogUrl),
                    displayName = feed.title,
                    generation = GENERATION_OPDS_1,
                    distUrl = catalogUrl,
                    lang = feed.language.orEmpty(),
                    iconUrl = "",
                ),
            ),
        )
        if (outcome.conflicts.isNotEmpty()) {
            // Unreachable by construction (the identity derives from this
            // same URL), but a registration conflict must never pass silent.
            throw SourcePluginException(
                SourceError.Incompatible(
                    "Catalog \"$catalogUrl\" is already registered from another repository",
                ),
                null,
            )
        }
        return CatalogAdded(
            catalogUrl = catalogUrl,
            title = feed.title,
            created = outcome.created > 0,
        )
    }
}
