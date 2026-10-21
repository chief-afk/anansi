package com.chief.anansi.ui.browse

import com.chief.anansi.data.entity.SourceEntity
import com.chief.anansi.data.repo.SourceEcosystem
import com.chief.anansi.plugin.JsSourceRuntime
import com.chief.anansi.plugin.Source
import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourceErrorRegistry
import com.chief.anansi.plugin.error.SourcePluginException
import com.chief.anansi.plugin.lnreader.v1era.V1EraPluginLoader
import com.chief.anansi.plugin.lnreader.v3.DEFAULT_IMAGE_USER_AGENT
import com.chief.anansi.plugin.lnreader.v3.V3ManifestItem
import com.chief.anansi.plugin.lnreader.v3.V3PluginLoader
import com.chief.anansi.plugin.lnreader.v3.V3Source
import com.chief.anansi.plugin.local.LocalLibrary
import com.chief.anansi.plugin.local.LocalSource
import com.chief.anansi.plugin.opds.OpdsHttp
import com.chief.anansi.plugin.opds.OpdsSource
import com.chief.anansi.plugin.repo.RepoHttp
import com.chief.anansi.plugin.repo.SourceGenerations
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * Loads registered sources into live [Source] adapters for the discovery
 * screens. Every load is fresh on purpose: a load creates a new engine slot,
 * so a screen never holds a slot another screen's load has replaced, and the
 * runtime keeps each plugin's storage scope across reloads, so saved filter
 * values survive. Dist text is cached per dist url to keep reloads off the
 * network. OPDS catalogs and the Local library skip the plugin path
 * entirely: they are zero-runtime source types (OPDS reads its registered
 * catalog URL, Local reads its stored file copies).
 */
class SourceSessions(
    private val runtime: JsSourceRuntime,
    private val errorRegistry: SourceErrorRegistry,
    private val http: RepoHttp,
    client: OkHttpClient,
    private val localLibrary: LocalLibrary,
) {
    private val opdsHttp = OpdsHttp(client)
    private val distCache = HashMap<String, String>()
    private val mutex = Mutex()

    /** Loads [entity]'s plugin into a fresh adapter; failures are loud. */
    suspend fun load(entity: SourceEntity): Source {
        if (entity.ecosystem == SourceEcosystem.LOCAL.key) {
            return LocalSource(
                library = localLibrary,
                registry = errorRegistry,
                id = entity.id,
                name = entity.displayName,
                lang = entity.lang,
            )
        }
        // A repo-less lnreader row is a v1 backup import's needs-source
        // placeholder: its plugin is not installed, so every source call
        // reports the missing plugin instead of a confusing empty-dist
        // fetch failure. Adding a repository carrying the plugin id claims
        // the row, and this branch stops applying.
        if (entity.ecosystem == SourceEcosystem.LNREADER.key && entity.distUrl.isEmpty()) {
            val error = SourceError.SourceMissing(
                "This novel's source is not installed: it came from a v1 backup with " +
                    "plugin id \"${entity.sourceId}\" (${entity.displayName}). Add a repository " +
                    "carrying plugin id \"${entity.sourceId}\" to link it",
            )
            errorRegistry.report(entity.sourceId, error)
            throw SourcePluginException(error)
        }
        if (entity.ecosystem == SourceEcosystem.OPDS.key) {
            require(entity.distUrl.isNotEmpty()) {
                "Source \"${entity.sourceId}\" has no catalog URL registered"
            }
            return OpdsSource(
                catalogUrl = entity.distUrl,
                http = opdsHttp,
                registry = errorRegistry,
                catalogId = entity.sourceId,
                id = entity.id,
                name = entity.displayName,
                lang = entity.lang,
            )
        }
        val dist = mutex.withLock {
            distCache.getOrPut(entity.distUrl) {
                http.getText(entity.distUrl, "Plugin \"${entity.sourceId}\" dist")
            }
        }
        return when (entity.generation) {
            SourceGenerations.V3 -> V3PluginLoader.load(
                runtime = runtime,
                registry = errorRegistry,
                sourceId = entity.id,
                item = V3ManifestItem(
                    // The adapter consumes id, name, lang, and iconUrl; the
                    // manifest-only fields are not part of the registration
                    // record and stay empty.
                    id = entity.sourceId,
                    name = entity.displayName,
                    site = "",
                    lang = entity.lang,
                    version = "",
                    url = "",
                    iconUrl = entity.iconUrl,
                ),
                dist = dist,
            )

            SourceGenerations.V1ERA -> V1EraPluginLoader.load(
                runtime = runtime,
                registry = errorRegistry,
                sourceId = entity.id,
                pluginId = entity.sourceId,
                dist = dist,
                name = entity.displayName,
                lang = entity.lang.ifEmpty { null },
                iconUrl = entity.iconUrl.ifEmpty { null },
            )

            else -> throw IllegalArgumentException(
                "Source \"${entity.sourceId}\" has no runtime loader for generation " +
                    "\"${entity.generation}\"",
            )
        }
    }
}

/**
 * The headers covers for [source] load with. V3 plugins carry their own
 * imageRequestInit (the adapter guarantees the default User-Agent inside
 * it); v1-era modules have no image-request concept, so their covers ride
 * the same browser-equivalent default the rest of the app's HTTP uses.
 */
fun coverHeaders(source: Source): Map<String, String> = when (source) {
    is V3Source -> source.imageRequestInit.headers
    else -> mapOf("User-Agent" to DEFAULT_IMAGE_USER_AGENT)
}
