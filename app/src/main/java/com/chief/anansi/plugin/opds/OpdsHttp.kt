package com.chief.anansi.plugin.opds

import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Kotlin-side HTTP for the OPDS layer: catalog feeds, OpenSearch
 * descriptions, and acquisition documents ride the app's single OkHttp
 * client with the same browser-equivalent defaults the rest of the app's
 * HTTP uses. No retry behavior, no challenge handling of any kind. HTTP
 * 401/403 fail loudly naming the status; no credential UI exists
 * (out-of-scope authentication).
 */
class OpdsHttp(private val client: OkHttpClient) {

    /** A fetched document: its decoded text and raw Content-Type header. */
    class Response(val text: String, val contentTypeHeader: String?) {

        /** The media type without parameters, lowercase; null when absent. */
        val mediaType: String?
            get() = contentTypeHeader?.substringBefore(';')?.trim()?.lowercase()?.ifEmpty { null }
    }

    suspend fun fetchText(url: String, what: String): Response = withContext(Dispatchers.IO) {
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
                when (response.code) {
                    401 -> throw networkFailure(
                        "$what requires authentication (HTTP 401); " +
                            "Anansi does not send catalog credentials",
                    )
                    403 -> throw networkFailure("$what was refused (HTTP 403)")
                }
                if (!response.isSuccessful) {
                    throw networkFailure("$what request failed: HTTP ${response.code}")
                }
                Response(response.body.string(), response.header("Content-Type"))
            }
        } catch (e: SourcePluginException) {
            throw e
        } catch (e: Exception) {
            throw networkFailure("$what could not be fetched: ${e.message}")
        }
    }

    private fun networkFailure(reason: String): SourcePluginException =
        SourcePluginException(SourceError.Network(reason), null)

    private companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/113.0.0.0 Mobile Safari/537.36"
    }
}
