package com.chief.anansi.plugin.opds

import java.net.URI
import java.security.MessageDigest

/**
 * Deterministic per-catalog identity:
 * the catalog URL is normalized (scheme and host lowercased, default ports
 * dropped, path and query kept, fragment dropped) and hashed, so re-adding
 * the same URL mints the same `opds_{sourceId}` identity every time and the
 * registry's unique-index upsert keeps it a single source. The hex form is
 * navigation-safe: identities ride in nav route path segments, which a raw
 * URL with slashes would break.
 */
object OpdsCatalogIdentity {

    /** The stable plugin-id string for one catalog URL. */
    fun sourceIdFor(rawCatalogUrl: String): String =
        digest(hexInput = normalize(rawCatalogUrl)).take(32)

    /**
     * The normalization the identity hashes: equivalence here is equivalence
     * as a catalog. Userinfo is kept so the identity always describes the
     * URL that is actually fetched.
     */
    fun normalize(rawCatalogUrl: String): String {
        val uri = URI(rawCatalogUrl.trim())
        val scheme = uri.scheme?.lowercase()
            ?: throw IllegalArgumentException("Catalog URL has no scheme: $rawCatalogUrl")
        val host = uri.host?.lowercase()?.removeSurrounding("[", "]")
            ?: throw IllegalArgumentException("Catalog URL has no host: $rawCatalogUrl")
        val defaultPort = if (scheme == "https") 443 else 80
        val port = uri.port
        return buildString {
            append(scheme).append("://")
            if (uri.rawUserInfo != null) {
                append(uri.rawUserInfo).append('@')
            }
            append(host)
            if (port != -1 && port != defaultPort) {
                append(':').append(port)
            }
            append(uri.rawPath.orEmpty())
            if (uri.rawQuery != null) {
                append('?').append(uri.rawQuery)
            }
        }
    }

    private fun digest(hexInput: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(hexInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
