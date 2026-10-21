package com.chief.anansi.plugin.runtime

import com.chief.anansi.plugin.JsPluginCallException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * End-to-end proof (spec: New tests item 2): the real published NovelFull
 * v3 dist (fixture: plugins/v3.0.0, id "novelfull") runs through the host
 * glue and the OkHttp fetch bridge, with recorded HTTP fixtures replayed
 * by an interceptor. Only runtime, bundle, shim, and bridge are exercised;
 * the adapter layer does not exist yet.
 */
class NovelFullV3EndToEndTest {

    private fun resourceText(name: String): String =
        javaClass.classLoader.getResource(name).readText()

    private fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(RecordedFixturesInterceptor())
        .build()

    @Test
    fun popularNovelsEndToEndThroughFetchBridge() {
        QuickJsSourceRuntime(
            hostScript = resourceText("pluginhost/host.js"),
            vendorScript = resourceText("pluginhost/vendor.js"),
            fetchBridge = FetchBridge(newClient()),
        ).use { runtime ->
            runBlocking {
                val plugin = runtime.load(
                    "novelfull",
                    resourceText("pluginhost/v3/novelfull/index.js"),
                )
                val inspection = Json.parseToJsonElement(plugin.inspect()).jsonObject
                assertEquals("novelfull", inspection.getValue("id").jsonPrimitive.content)
                assertEquals("NovelFull", inspection.getValue("name").jsonPrimitive.content)
                // The v3 generation exposes parseNovel and no v1-era parseNovelAndChapters.
                assertTrue(inspection.getValue("members").jsonObject.getValue("parseNovel").jsonPrimitive.content == "true")
                assertTrue(inspection.getValue("members").jsonObject.getValue("parseNovelAndChapters").jsonPrimitive.content == "false")

                val popular = Json.parseToJsonElement(
                    plugin.call("popularNovels", """[1, {"showLatestNovels": false}]"""),
                ).jsonArray
                assertEquals(20, popular.size)
                val first = popular[0].jsonObject
                assertEquals(
                    "Reincarnation Of The Strongest Sword God",
                    first.getValue("name").jsonPrimitive.content,
                )
                assertEquals(
                    "reincarnation-of-the-strongest-sword-god.html",
                    first.getValue("path").jsonPrimitive.content,
                )
                assertTrue(
                    first.getValue("cover").jsonPrimitive.content
                        .startsWith("https://novelfull.com/"),
                )
                plugin.close()
            }
        }
    }

    @Test
    fun parseChapterEndToEndThroughFetchBridge() {
        QuickJsSourceRuntime(
            hostScript = resourceText("pluginhost/host.js"),
            vendorScript = resourceText("pluginhost/vendor.js"),
            fetchBridge = FetchBridge(newClient()),
        ).use { runtime ->
            runBlocking {
                val plugin = runtime.load(
                    "novelfull",
                    resourceText("pluginhost/v3/novelfull/index.js"),
                )
                val chapter = Json.parseToJsonElement(
                    plugin.call(
                        "parseChapter",
                        """["reincarnation-of-the-strongest-sword-god/chapter-1-starting-over.html"]""",
                    ),
                ).jsonPrimitive.content
                assertTrue(chapter.length > 5000)
                assertTrue(chapter.contains("Chapter 1: Starting Over"))
                plugin.close()
            }
        }
    }

    @Test
    fun unrecordedUrlFailsLoudly() {
        QuickJsSourceRuntime(
            hostScript = resourceText("pluginhost/host.js"),
            vendorScript = resourceText("pluginhost/vendor.js"),
            fetchBridge = FetchBridge(newClient()),
        ).use { runtime ->
            runBlocking {
                val plugin = runtime.load(
                    "novelfull",
                    resourceText("pluginhost/v3/novelfull/index.js"),
                )
                try {
                    plugin.call("parseChapter", """["not/recorded.html"]""")
                    fail("unrecorded fixture URL must fail loudly")
                } catch (e: JsPluginCallException) {
                    assertTrue(e.message!!.contains("No recorded fixture"))
                }
                plugin.close()
            }
        }
    }

    /** Replays recorded URL-to-response pairs; an unknown URL fails loudly. */
    private class RecordedFixturesInterceptor : Interceptor {

        private val fixtures: Map<String, RecordedResponse> = loadFixtures()

        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url.toString()
            val fixture = fixtures[url]
                ?: throw IllegalStateException("No recorded fixture for $url")
            val body = RecordedFixturesInterceptor::class.java
                .classLoader.getResource("pluginhost/recorded/" + fixture.file)
                .readText()
                .toResponseBody("text/html; charset=utf-8".toMediaType())
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(fixture.status)
                .message(fixture.statusText)
                .body(body)
                .build()
        }

        private fun loadFixtures(): Map<String, RecordedResponse> {
            val root = Json.parseToJsonElement(
                RecordedFixturesInterceptor::class.java
                    .classLoader.getResource("pluginhost/recorded/fixtures.json").readText(),
            ).jsonObject
            return root.entries.associate { (url, value) ->
                val obj = value.jsonObject
                url to RecordedResponse(
                    status = obj.getValue("status").jsonPrimitive.content.toInt(),
                    statusText = obj.getValue("statusText").jsonPrimitive.content,
                    file = obj.getValue("file").jsonPrimitive.content,
                )
            }
        }

        private data class RecordedResponse(val status: Int, val statusText: String, val file: String)
    }
}
