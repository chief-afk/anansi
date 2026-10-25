package com.chief.anansi.plugin.runtime

import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * OkHttp-backed fetch executor for plugin dists. Browser-equivalent HTTP
 * only: default headers per the LNReader fetch shim, no retry behavior,
 * no challenge handling of any kind. Fetch targets resolving to private or
 * local addresses are rejected before any connection (app posture: plugins
 * reach the public web, not the device's localhost or LAN), response bodies
 * and gunzip output are capped at [maxResponseBytes], and caller
 * cancellation aborts the in-flight HTTP call.
 *
 * Wire format (id-multiplexed, shared by every engine outcome):
 * request `{"id":N,"url":"..","method":"GET","headers":{},"body"?:str,"bodyBase64"?:str}`;
 * response `{"id":N,"ok":{"status":N,"statusText":"..","headers":{lower:val},"url":"..","body"?:str,"bodyBase64"?:str}}`
 * or `{"id":N,"err":"message"}`. Textual bodies ride as `body` (UTF-8),
 * binary ones as `bodyBase64`.
 */
class FetchBridge(
    private val client: OkHttpClient,
    private val maxResponseBytes: Long = SourceRuntimeLimits.MAX_RESPONSE_BYTES,
) {

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
        val blockReason = withContext(Dispatchers.IO) { privateTargetBlock(httpUrl) }
        if (blockReason != null) {
            return errorEnvelope(request.id, blockReason)
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

            val response = suspendCancellableCoroutine { continuation ->
                val call = client.newCall(requestBuilder.build())
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        if (continuation.isCancelled) {
                            response.close()
                        } else {
                            continuation.resume(response)
                        }
                    }

                    override fun onFailure(call: Call, e: java.io.IOException) {
                        if (!continuation.isCancelled) continuation.resumeWithException(e)
                    }
                })
            }
            response.use {
                withContext(Dispatchers.IO) {
                    val raw = readBodyCapped(it.body, maxResponseBytes)
                    val decoded = gunzipIfGzip(it.header("Content-Encoding"), raw, maxResponseBytes)
                    val headers = it.headers.names().associateWith { name -> it.header(name) ?: "" }
                    okEnvelope(request.id, it.code, it.message, headers, it.request.url.toString(), decoded)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FetchCapExceeded) {
            errorEnvelope(request.id, e.message ?: capMessage(maxResponseBytes))
        } catch (e: Exception) {
            errorEnvelope(request.id, "Fetch failed for ${request.url}: ${e.message}")
        }
    }

    /**
     * Resolves the target host and reports a private or local target before
     * any connection. DNS failures pass through so the real connect produces
     * the honest network error. Stated residual risk: OkHttp re-resolves DNS
     * when connecting, so a hostname that flips to a private address between
     * check and connect is not caught; no proxy pinning is attempted.
     */
    private fun privateTargetBlock(url: HttpUrl): String? {
        val host = url.host
        if (host.equals("localhost", ignoreCase = true)) {
            return blockedTargetMessage(host)
        }
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return null
        return if (addresses.any { isPrivateOrLocal(it) }) {
            blockedTargetMessage(host)
        } else {
            null
        }
    }

    private fun blockedTargetMessage(host: String): String =
        "Blocked fetch target: $host is a private or local address"

    private fun isPrivateOrLocal(address: InetAddress): Boolean =
        address.isLoopbackAddress ||
            address.isAnyLocalAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            (address is Inet6Address && (address.address[0].toInt() and UNIQUE_LOCAL_IPV6_MASK) == 0xfc)

    /** Streams the body in chunks so an oversized response never materializes. */
    private fun readBodyCapped(body: ResponseBody, limit: Long): ByteArray {
        body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(COPY_CHUNK_BYTES)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read < 0) return output.toByteArray()
                total += read
                if (total > limit) throw FetchCapExceeded(capMessage(limit))
                output.write(chunk, 0, read)
            }
        }
    }

    private fun gunzipIfGzip(contentEncoding: String?, bytes: ByteArray, limit: Long): ByteArray {
        if (bytes.isEmpty() || !contentEncoding.equals("gzip", ignoreCase = true)) {
            return bytes
        }
        return try {
            GZIPInputStream(bytes.inputStream()).use { stream ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(COPY_CHUNK_BYTES)
                var total = 0L
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > limit) throw FetchCapExceeded(capMessage(limit))
                    output.write(chunk, 0, read)
                }
                output.toByteArray()
            }
        } catch (e: FetchCapExceeded) {
            throw e
        } catch (e: Exception) {
            bytes
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

    /** Thrown past the gunzip fallback so the cap error is never swallowed. */
    private class FetchCapExceeded(message: String) : RuntimeException(message)

    private fun capMessage(limit: Long): String =
        "Response body exceeded the ${describeCap(limit)} fetch cap"

    private fun describeCap(limit: Long): String =
        if (limit >= BYTES_PER_MB && limit % BYTES_PER_MB == 0L) {
            "${limit / BYTES_PER_MB} MB"
        } else {
            "$limit bytes"
        }

    private companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/113.0.0.0 Mobile Safari/537.36"
        const val COPY_CHUNK_BYTES = 64 * 1024
        const val BYTES_PER_MB = 1024L * 1024
        const val UNIQUE_LOCAL_IPV6_MASK = 0xfe
    }
}
