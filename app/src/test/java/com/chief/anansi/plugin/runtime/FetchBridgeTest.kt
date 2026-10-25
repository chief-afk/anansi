package com.chief.anansi.plugin.runtime

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bridge contract tests (spec: New tests): interceptor-replayed HTTP proves
 * the private-range block issues no request, byte and gunzip caps abort with
 * the honest error, and caller cancellation cancels the HTTP call.
 */
class FetchBridgeTest {

    private fun envelopeJson(jsonText: String) = Json.parseToJsonElement(jsonText).jsonObject

    private fun fixedBodyClient(
        body: ByteArray,
        contentType: String = "text/plain",
        extraHeaders: Map<String, String> = emptyMap(),
        requests: AtomicInteger? = null,
    ): OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        requests?.incrementAndGet()
        Response.Builder()
            .request(chain.request())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody(contentType.toMediaType()))
            .apply { extraHeaders.forEach { (name, value) -> header(name, value) } }
            .build()
    }).build()

    private suspend fun execute(bridge: FetchBridge, url: String): String =
        bridge.execute("""{"id":7,"url":"$url","method":"GET"}""")

    @Test
    fun oversizedBodyAbortsHonestCapError() = runBlocking {
        val requests = AtomicInteger(0)
        val bridge = FetchBridge(
            fixedBodyClient(ByteArray(4096) { 'x'.code.toByte() }, requests = requests),
            maxResponseBytes = 1024,
        )
        val envelope = envelopeJson(execute(bridge, "https://cap.test/big"))
        assertEquals("7", envelope.getValue("id").jsonPrimitive.content)
        val err = envelope.getValue("err").jsonPrimitive.content
        assertTrue("cap error: $err", err.contains("exceeded"))
        assertTrue("cap error: $err", err.contains("fetch cap"))
        assertEquals(1, requests.get())
    }

    @Test
    fun oversizedGunzipOutputAbortsCapError() = runBlocking {
        val gzipped = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(ByteArray(4096)) }
        }.toByteArray()
        val bridge = FetchBridge(
            fixedBodyClient(
                gzipped,
                contentType = "application/octet-stream",
                extraHeaders = mapOf("Content-Encoding" to "gzip"),
            ),
            maxResponseBytes = 1024,
        )
        val envelope = envelopeJson(execute(bridge, "https://cap.test/gzipped"))
        val err = envelope.getValue("err").jsonPrimitive.content
        assertTrue("gunzip cap error: $err", err.contains("fetch cap"))
    }

    @Test
    fun privateTargetsAreBlockedBeforeAnyRequest() = runBlocking {
        val requests = AtomicInteger(0)
        val bridge = FetchBridge(fixedBodyClient(ByteArray(16), requests = requests))
        for (host in listOf("localhost", "127.0.0.1", "10.1.2.3", "192.168.0.5", "169.254.9.9")) {
            val envelope = envelopeJson(execute(bridge, "http://$host/page"))
            val err = envelope.getValue("err").jsonPrimitive.content
            assertTrue(
                "'$host' must be blocked, got: $err",
                err.startsWith("Blocked fetch target: ") && err.contains(host),
            )
        }
        assertEquals(0, requests.get())
    }

    @Test
    fun cancelledCallerCancelsTheHttpCall() {
        val interceptorStarted = CompletableDeferred<Unit>()
        val observedCall = AtomicReference<Call?>(null)
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                observedCall.set(chain.call())
                interceptorStarted.complete(Unit)
                // Hold the request open until the test has cancelled it; a
                // cancelled caller must reach the underlying call.
                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(10)
                }
                throw IOException("cancelled under test")
            })
            .build()
        val bridge = FetchBridge(client)
        runBlocking {
            val job = launch {
                // A literal public address parses without DNS, so the
                // interceptor starts deterministically; the interceptor
                // short-circuits before any connection is attempted.
                bridge.execute("""{"id":1,"url":"http://1.2.3.4/slow","method":"GET"}""")
            }
            // Suspended wait: the event loop stays free to drive execute up
            // to its enqueue, which is what completes the deferred.
            withTimeout(15_000) { interceptorStarted.await() }
            job.cancel()
            job.join()
            assertTrue("caller cancellation must fail the job", job.isCancelled)
            // cancel() ran on this thread, so the read needs no fencing.
            assertTrue(
                "invokeOnCancellation must cancel the HTTP call",
                observedCall.get()!!.isCanceled(),
            )
        }
    }
}
