package com.chief.anansi.plugin.runtime

import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Host shim fixture tests (spec: Verification, new tests): FormData posts
 * serialize as multipart with the instance's boundary, Headers instances
 * reach the request, AbortController rejects per the pinned JS-side
 * semantics, atob/btoa round-trip Latin1, and any Intl member access fails
 * loudly through the unsupported-feature marker.
 */
class HostShimFixtureTest {

    private fun resourceText(name: String): String =
        javaClass.classLoader.getResource(name).readText()

    /** Records every request (with its exact body bytes), answers 200 {}. */
    private class CapturingInterceptor(
        private val onRequest: (Request, ByteArray) -> Unit = { _, _ -> },
    ) : Interceptor {
        val requests = mutableListOf<Pair<Request, ByteArray>>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val bodyBytes = Buffer().also { request.body?.writeTo(it) }.readByteArray()
            synchronized(requests) { requests += request to bodyBytes }
            onRequest(request, bodyBytes)
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun newRuntime(
        client: OkHttpClient = OkHttpClient(),
        onLog: (level: String, message: String) -> Unit = { _, _ -> },
    ): QuickJsSourceRuntime =
        QuickJsSourceRuntime(
            hostScript = resourceText("pluginhost/host.js"),
            vendorScript = resourceText("pluginhost/vendor.js"),
            fetchBridge = FetchBridge(client),
            onLog = onLog,
        )

    private fun callProbe(runtime: QuickJsSourceRuntime, dist: String): String {
        runtime.use { r ->
            return runBlocking {
                val plugin = r.load("fixture-shim", dist)
                try {
                    plugin.call("probe", "[]")
                } finally {
                    plugin.close()
                }
            }
        }
    }

    @Test
    fun formDataWithStringsAndFilePostsMultipartBodyWithBoundary() {
        val interceptor = CapturingInterceptor()
        val result = callProbe(
            newRuntime(OkHttpClient.Builder().addInterceptor(interceptor).build()),
            "exports.default = { " +
                "popularNovels: async function () { return []; }, " +
                "probe: async function () { " +
                "const form = new FormData(); " +
                "form.append(\"listing\", \"hot\"); " +
                "form.append(\"note\", \"line1\\r\\nline2\"); " +
                "form.append(\"upload\", new File([1, 2, 3], 'report \"a\".txt', \"text/plain\")); " +
                "await fetch(\"https://fixture.invalid/upload\", { method: \"POST\", body: form }); " +
                "return { sent: true }; } };",
        )
        assertTrue("probe result was: $result", result.contains("\"sent\":true"))
        assertEquals(1, interceptor.requests.size)
        val (request, body) = interceptor.requests[0]
        val contentType = request.header("Content-Type")
        assertTrue(
            "expected a multipart Content-Type with boundary, was: $contentType",
            contentType != null && contentType.startsWith("multipart/form-data; boundary=----anansi"),
        )
        val boundary = contentType!!.removePrefix("multipart/form-data; boundary=")
        val head = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"listing\"\r\n\r\n")
            append("hot\r\n")
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"note\"\r\n\r\n")
            append("line1\r\nline2\r\n")
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"upload\"; ")
            append("filename=\"report %22a%22.txt\"\r\n")
            append("Content-Type: text/plain\r\n\r\n")
        }
        val tail = "\r\n--$boundary--\r\n"
        val expected = head.toByteArray(Charsets.UTF_8) +
            byteArrayOf(1, 2, 3) +
            tail.toByteArray(Charsets.UTF_8)
        assertTrue(
            "multipart body bytes mismatch:\n${body.toString(Charsets.UTF_8)}",
            body.contentEquals(expected),
        )
    }

    @Test
    fun headersInstanceEntriesReachRequestHeaders() {
        val interceptor = CapturingInterceptor()
        val result = callProbe(
            newRuntime(OkHttpClient.Builder().addInterceptor(interceptor).build()),
            "exports.default = { " +
                "popularNovels: async function () { return []; }, " +
                "probe: async function () { " +
                "const headers = new Headers({ \"X-Keep\": \"first\" }); " +
                "headers.append(\"X-Keep\", \"second\"); " +
                "headers.set(\"x-final\", \"yes\"); " +
                "await fetch(\"https://fixture.invalid/h\", { headers: headers }); " +
                "return { " +
                "first: headers.get(\"X-KEEP\"), " +
                "hasFinal: headers.has(\"x-final\"), " +
                "entryCount: headers.entries().length }; } };",
        )
        assertTrue("probe result was: $result", result.contains("\"first\":\"first\""))
        assertTrue("probe result was: $result", result.contains("\"hasFinal\":true"))
        assertTrue("probe result was: $result", result.contains("\"entryCount\":3"))
        assertEquals(1, interceptor.requests.size)
        assertEquals("second", interceptor.requests[0].first.header("X-Keep"))
        assertEquals("yes", interceptor.requests[0].first.header("X-Final"))
    }

    @Test
    fun alreadyAbortedSignalRejectsWithoutReachingHttp() {
        val interceptor = CapturingInterceptor()
        val result = callProbe(
            newRuntime(OkHttpClient.Builder().addInterceptor(interceptor).build()),
            "exports.default = { " +
                "popularNovels: async function () { return []; }, " +
                "probe: async function () { " +
                "const controller = new AbortController(); " +
                "controller.abort(); " +
                "try { " +
                "await fetch(\"https://fixture.invalid/never\", { signal: controller.signal }); " +
                "return { outcome: \"resolved\" }; " +
                "} catch (e) { return { outcome: e.name + \":\" + (e instanceof Error) }; } } };",
        )
        assertTrue(
            "probe result was: $result",
            result.contains("\"outcome\":\"AbortError:true\""),
        )
        assertEquals(0, interceptor.requests.size)
    }

    @Test
    fun abortMidFlightRejectsAwaitedPromiseAndFiresListener() {
        val fetchArrived = CountDownLatch(1)
        val abortObserved = CountDownLatch(1)
        val fetchRelease = CountDownLatch(1)
        val hangingClient = OkHttpClient.Builder()
            .addInterceptor {
                fetchArrived.countDown()
                fetchRelease.await()
                throw java.io.IOException("test fetch released")
            }
            .build()
        try {
            newRuntime(
                client = hangingClient,
                onLog = { _, message ->
                    if (message.startsWith("abort-result:")) abortObserved.countDown()
                },
            ).use { runtime ->
                runBlocking {
                    val plugin = runtime.load(
                        "fixture-shim",
                        "exports.default = { " +
                            "popularNovels: async function () { return []; }, " +
                            "probe: async function () { " +
                            "const controller = new AbortController(); " +
                            "let fired = false; " +
                            "controller.signal.addEventListener(\"abort\", function () { fired = true; }); " +
                            "Promise.resolve().then(function () { controller.abort(); }); " +
                            "let outcome; " +
                            "try { " +
                            "await fetch(\"http://192.0.2.1/hang\", { signal: controller.signal }); " +
                            "outcome = \"resolved\"; " +
                            "} catch (e) { " +
                            "outcome = \"caught:\" + e.name + \":\" + fired + \":\" + controller.signal.aborted; } " +
                            "console.log(\"abort-result:\" + outcome); " +
                            "return { outcome: outcome }; } };",
                    )
                    val parked = async(Dispatchers.IO) { plugin.call("probe", "[]") }
                    assertTrue(
                        "the plugin fetch never went in flight",
                        fetchArrived.await(10, TimeUnit.SECONDS),
                    )
                    assertTrue(
                        "the mid-flight abort never settled the plugin's await",
                        abortObserved.await(10, TimeUnit.SECONDS),
                    )
                    fetchRelease.countDown()
                    assertTrue(
                        "the parked call must return the abort outcome",
                        parked.await().contains("\"outcome\":\"caught:AbortError:true:true\""),
                    )
                    plugin.close()
                }
            }
        } finally {
            fetchRelease.countDown()
        }
    }

    @Test
    fun atobBtoaRoundTripBothDirectionsAndRejectNonLatin1() {
        val result = callProbe(
            newRuntime(),
            "exports.default = { " +
                "popularNovels: async function () { return []; }, " +
                "probe: async function () { " +
                "const s = \"hello \\u00e9\\u00ff!\"; " +
                "let nonLatin1 = \"\"; " +
                "try { btoa(\"a\\u1234\"); nonLatin1 = \"no-throw\"; } " +
                "catch (e) { nonLatin1 = e.name + \":\" + (e.message.indexOf(\"Latin1\") >= 0); } " +
                "return { " +
                "encoded: btoa(s), " +
                "roundTrip: atob(btoa(s)) === s, " +
                "emptyOk: btoa(\"\") === \"\" && atob(\"\") === \"\", " +
                "nonLatin1: nonLatin1 }; } };",
        )
        assertTrue("probe result was: $result", result.contains("\"encoded\":\"aGVsbG8g6f8h\""))
        assertTrue("probe result was: $result", result.contains("\"roundTrip\":true"))
        assertTrue("probe result was: $result", result.contains("\"emptyOk\":true"))
        assertTrue("probe result was: $result", result.contains("\"nonLatin1\":\"TypeError:true\""))
    }

    @Test
    fun intlMemberAccessFailsAsUnsupportedFeature() {
        newRuntime().use { runtime ->
            runBlocking {
                val plugin = runtime.load(
                    "fixture-shim",
                    "exports.default = { " +
                        "popularNovels: async function () { return []; }, " +
                        "probe: async function () { return String(new Intl.DateTimeFormat(\"en\")); } };",
                )
                try {
                    plugin.call("probe", "[]")
                    fail("Intl member access must throw")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.UnsupportedFeature)
                    assertTrue(e.message!!.contains("Intl"))
                }
                plugin.close()
            }
        }
    }
}
