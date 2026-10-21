package com.chief.anansi.plugin.repo

import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Kotlin-side HTTP for the repo layer: manifest and dist fetches ride the
 * app's single OkHttp client with the same browser-equivalent defaults the
 * plugin fetch bridge uses. No retry behavior, no challenge handling of any
 * kind.
 */
class RepoHttp(private val client: OkHttpClient) {

    /** Fetches a document as text; failures are loud [SourceError.Network]s. */
    suspend fun getText(url: String, what: String): String = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder()
                .url(url)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "*/*")
                .build()
        } catch (e: Exception) {
            throw networkFailure("$what could not be requested: ${e.message}")
        }
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw networkFailure("$what request failed: HTTP ${response.code}")
                }
                response.body.string()
            }
        } catch (e: SourcePluginException) {
            throw e
        } catch (e: Exception) {
            throw networkFailure("$what could not be fetched: ${e.message}")
        }
    }

    private fun networkFailure(reason: String): SourcePluginException =
        SourcePluginException(SourceError.Network(reason))

    private companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/113.0.0.0 Mobile Safari/537.36"
    }
}
