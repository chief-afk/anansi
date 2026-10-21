package com.chief.anansi.plugin.repo

import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import java.net.URI

/**
 * Repository URL gate (the lnreader-v1 `assertRepoUrl` rule, reimplemented
 * from its documented behavior): repositories must be https://; plain http
 * is accepted only for loopback hosts so the emulator can reach a local
 * test server. The host comes from parsed URL data, never from a string
 * prefix, which a userinfo spoof (`http://localhost@evil.com`) defeats.
 */
object RepoUrls {

    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

    /** Returns the trimmed URL when allowed; otherwise fails with a visible reason. */
    fun validate(rawUrl: String): String {
        val url = rawUrl.trim()
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw repoUrlFailure("Repository URL is not a valid URL")
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removeSurrounding("[", "]")
        if (scheme == null || host == null) {
            throw repoUrlFailure("Repository URL is not a valid URL")
        }
        if (scheme == "https") return url
        if (scheme == "http" && host in LOOPBACK_HOSTS) return url
        throw repoUrlFailure(
            "Repository URL must be an https:// URL " +
                "(plain http is accepted only for localhost, 127.0.0.1, ::1, and 10.0.2.2)",
        )
    }

    private fun repoUrlFailure(reason: String): SourcePluginException =
        SourcePluginException(SourceError.Parse(reason))
}
