package com.chief.anansi.plugin.runtime

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OkHttp-backed fetch executor for plugin dists. Browser-equivalent HTTP
 * only: default headers per the LNReader fetch shim, no retry behavior,
 * no challenge handling of any kind.
 *
 * Wire format (id-multiplexed, shared by every engine outcome):
 * request `{"id":N,"url":"..","method":"GET","headers":{},"body"?:str,"bodyBase64"?:str}`;
 * response `{"id":N,"ok":{"status":N,"statusText":"..","headers":{lower:val},"url":"..","body"?:str,"bodyBase64"?:str}}`
 * or `{"id":N,"err":"message"}`. Textual bodies ride as `body` (UTF-8),
 * binary ones as `bodyBase64`.
 */
class FetchBridge(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Browser-equivalent defaults from the LNReader fetch shim. Only
     * `Accept-Encoding` is omitted: OkHttp negotiates and decompresses
     * gzip transparently, which is what a browser does with the header.
     */
    private val defaultHeaders: Map<String, String> = mapOf(
        "Connection" to "keep-alive",
        "Accept" to "*/*",
        "Accept-Language" to "*",
        "Sec-Fetch-Mode" to "cors",
        "Cache-Control" to "max-age=0",
        "User-Agent" to DEFAULT_USER_AGENT,
    )

    suspend fun execute(requestJson: String): String {
        val request = try {
            parseRequest(requestJson)
        } catch (e: Exception) {
            return errorEnvelope(-1, "Malformed fetch request: ${e.message}")
        }

        val httpUrl = request.url.toHttpUrlOrNull()
        if (httpUrl == null || (httpUrl.scheme != "http" && httpUrl.scheme != "https")) {
            return errorEnvelope(request.id, "Unsupported fetch URL: ${request.url}")
        }

        return try {
            val requestBuilder = Request.Builder().url(httpUrl)
            val bodyBytes = request.bodyBytes
            when (request.method) {
                "GET", "HEAD" -> requestBuilder.method(request.method, null)
                else -> {
                    val contentType = request.headers.entries
                        .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                        ?.let { it.value.toMediaTypeOrNull() }
                    val payload = bodyBytes ?: ByteArray(0)
                    requestBuilder.method(request.method, payload.toRequestBody(contentType))
                }
            }
            defaultHeaders.forEach { (name, value) -> requestBuilder.header(name, value) }
            request.headers.forEach { (name, value) -> requestBuilder.header(name, value) }

            val call = client.newCall(requestBuilder.build())
            val response = withContext(Dispatchers.IO) { call.execute() }
            response.use {
                val raw = withContext(Dispatchers.IO) { it.body.bytes() }
                val decoded = gunzipIfGzip(it.header("Content-Encoding"), raw)
                val headers = it.headers.names().associateWith { name -> it.header(name) ?: "" }
                okEnvelope(request.id, it.code, it.message, headers, it.request.url.toString(), decoded)
            }
        } catch (e: Exception) {
            errorEnvelope(request.id, "Fetch failed for ${request.url}: ${e.message}")
        }
    }

    private data class ParsedRequest(
        val id: Long,
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val bodyBytes: ByteArray?,
    )

    private fun parseRequest(requestJson: String): ParsedRequest {
        val obj = json.parseToJsonElement(requestJson).jsonObject
        val headers = obj["headers"]?.jsonObject?.entries?.associate {
            it.key to it.value.jsonPrimitive.content
        } ?: emptyMap()
        val bodyBase64 = obj["bodyBase64"]
        val body = obj["body"]
        val bodyBytes = when {
            bodyBase64 != null -> Base64.getDecoder().decode(bodyBase64.jsonPrimitive.content)
            body != null -> body.jsonPrimitive.content.toByteArray(Charsets.UTF_8)
            else -> null
        }
        return ParsedRequest(
            id = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: -1,
            url = obj.getValue("url").jsonPrimitive.content,
            method = (obj["method"]?.jsonPrimitive?.content ?: "GET").uppercase(),
            headers = headers,
            bodyBytes = bodyBytes,
        )
    }

    private fun gunzipIfGzip(contentEncoding: String?, bytes: ByteArray): ByteArray {
        if (bytes.isEmpty() || !contentEncoding.equals("gzip", ignoreCase = true)) {
            return bytes
        }
        return try {
            GZIPInputStream(bytes.inputStream()).use { stream ->
                val out = ByteArrayOutputStream()
                stream.copyTo(out)
                out.toByteArray()
            }
        } catch (e: Exception) {
            bytes
        }
    }

    private fun okEnvelope(
        id: Long,
        status: Int,
        statusText: String,
        headers: Map<String, String>,
        url: String,
        body: ByteArray,
    ): String {
        val lowered = JsonObject(headers.entries.associate { (k, v) -> k.lowercase() to JsonPrimitive(v) })
        val payload = buildJsonObject {
            put("id", id)
            put(
                "ok",
                buildJsonObject {
                    put("status", status)
                    put("statusText", statusText)
                    put("headers", lowered)
                    put("url", url)
                },
            )
        }
        val contentType = headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value.orEmpty().lowercase()
        val isTextual = contentType.isEmpty() || contentType.startsWith("text/") ||
            contentType.contains("json") || contentType.contains("xml") ||
            contentType.contains("javascript") || contentType.contains("urlencoded")
        val bodyField = if (body.isEmpty()) {
            emptyMap()
        } else if (isTextual) {
            mapOf("body" to JsonPrimitive(body.toString(Charsets.UTF_8)))
        } else {
            mapOf("bodyBase64" to JsonPrimitive(Base64.getEncoder().encodeToString(body)))
        }
        val withBody = payload["ok"]?.jsonObject?.let { ok ->
            val merged = JsonObject(ok + bodyField)
            JsonObject(payload + mapOf("ok" to merged))
        } ?: payload
        return withBody.toString()
    }

    private fun errorEnvelope(id: Long, message: String): String = buildJsonObject {
        put("id", id)
        put("err", message)
    }.toString()

    private companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/113.0.0.0 Mobile Safari/537.36"
    }
}
