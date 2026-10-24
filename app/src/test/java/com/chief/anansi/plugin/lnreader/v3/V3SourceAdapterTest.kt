package com.chief.anansi.plugin.lnreader.v3

import com.chief.anansi.plugin.error.InMemorySourceErrorRegistry
import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import com.chief.anansi.plugin.runtime.FetchBridge
import com.chief.anansi.plugin.runtime.QuickJsSourceRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v3 adapter fixture tests (spec: New tests items 2-5): the happy path
 * through recorded HTTP fixtures, the loud generation check, the loud
 * @libs/aes rejection, and the filter/settings storage round trip.
 */
class V3SourceAdapterTest {

    private fun resourceText(name: String): String =
        javaClass.classLoader.getResource(name).readText()

    private fun newRuntime(): QuickJsSourceRuntime = QuickJsSourceRuntime(
        hostScript = resourceText("pluginhost/host.js"),
        vendorScript = resourceText("pluginhost/vendor.js"),
        fetchBridge = FetchBridge(recordedClient()),
    )

    private fun goodItem() = V3ManifestItem(
        id = "fixture-v3",
        name = "Fixture V3",
        site = "https://novelfull.com/",
        lang = "en",
        version = "1.0.0",
        url = "good-dist.js",
        iconUrl = "https://novelfull.com/icon.png",
    )

    private fun item(id: String) = V3ManifestItem(
        id = id,
        name = "Fixture",
        site = "https://fixture.invalid/",
        lang = "en",
        version = "1.0.0",
        url = "$id.js",
        iconUrl = "https://fixture.invalid/icon.png",
    )

    private fun load(
        runtime: QuickJsSourceRuntime,
        registry: InMemorySourceErrorRegistry,
        item: V3ManifestItem,
        dist: String,
    ): V3Source = runBlocking {
        V3PluginLoader.load(runtime, registry, sourceId = 1L, item = item, dist = dist)
    }

    @Test
    fun happyPathMappingsThroughRecordedFixtures() {
        newRuntime().use { runtime ->
            val registry = InMemorySourceErrorRegistry()
            runBlocking {
                val source = load(runtime, registry, goodItem(), resourceText(GOOD_DIST))

                assertEquals("fixture-v3", source.pluginId)
                assertEquals("lnreader", source.ecosystem)
                assertEquals("v3", source.generation)
                assertEquals("Fixture V3", source.name)
                assertEquals("en", source.lang)

                val popular = source.popularNovels(1, showLatestNovels = false, filterValuesJson = null)
                assertEquals(20, popular.size)
                assertEquals("Reincarnation Of The Strongest Sword God", popular[0].name)
                assertEquals("reincarnation-of-the-strongest-sword-god.html", popular[0].path)
                assertTrue(popular[0].cover!!.startsWith("https://novelfull.com/"))

                val search = source.searchNovels("sword", 1, filterValuesJson = null)
                assertTrue(search.size > 10)
                assertTrue(search.any { it.path == "reincarnation-of-the-strongest-sword-god.html" })

                val details = source.novelDetails("reincarnation-of-the-strongest-sword-god.html")
                assertEquals("Reincarnation Of The Strongest Sword God", details.name)
                assertTrue(details.author!!.contains("Lucky Old Cat"))
                assertTrue(details.genres!!.contains("Action"))
                assertEquals("Completed", details.status)
                assertTrue(details.summary!!.contains("living game"))
                assertTrue(details.cover!!.startsWith("https://novelfull.com/"))
                assertEquals(50, details.chapters.size)
                val chapterOne = details.chapters.first {
                    it.path == "reincarnation-of-the-strongest-sword-god/chapter-1-starting-over.html"
                }
                assertEquals("Chapter 1 - Starting Over", chapterOne.name)
                assertEquals(1.0, chapterOne.chapterNumber!!, 0.0)

                val text = source.chapterText(
                    novelPath = "reincarnation-of-the-strongest-sword-god.html",
                    chapterPath = "reincarnation-of-the-strongest-sword-god/chapter-1-starting-over.html",
                )
                assertTrue(text.length > 1000)
                assertTrue(text.contains("Chapter 1: Starting Over"))
                // Normalized to plain text: element markup is gone and
                // paragraph breaks survive. (Angle brackets that appear are
                // novel content decoded from entities, e.g. <God's Domain>.)
                assertTrue(!text.contains("<p>"))
                assertTrue(!text.contains("</p>"))
                assertTrue(!text.contains("<div"))
                assertTrue(text.contains("\n\n"))

                // Filter and settings schemas pass through as plugin JSON.
                val filters = Json.parseToJsonElement(source.filters.toString()).jsonObject
                assertEquals(
                    "Picker",
                    filters.getValue("limit").jsonObject.getValue("type").jsonPrimitive.content,
                )
                val settings = Json.parseToJsonElement(source.settings.toString()).jsonObject
                assertEquals(
                    "Select",
                    settings.getValue("prefix").jsonObject.getValue("type").jsonPrimitive.content,
                )

                // The plugin's Referer header survives; the default User-Agent is injected.
                assertEquals("https://novelfull.com/", source.imageRequestInit.headers["Referer"])
                assertEquals(DEFAULT_IMAGE_USER_AGENT, source.imageRequestInit.headers["User-Agent"])

                // A successful run leaves the error registry clear.
                assertNull(registry.latest("fixture-v3"))
                source.close()
            }
        }
    }

    @Test
    fun wrongGenerationFailsLoudly() {
        newRuntime().use { runtime ->
            val registry = InMemorySourceErrorRegistry()
            runBlocking {
                try {
                    load(runtime, registry, item("fixture-wrong-generation"), resourceText(WRONG_GENERATION_DIST))
                    fail("a v1-era dist must be rejected by the v3 adapter")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.Incompatible)
                    assertTrue(e.message!!.contains("v1-era plugin loaded by the v3 adapter"))
                }
                val recorded = registry.latest("fixture-wrong-generation")
                assertTrue(recorded is SourceError.Incompatible)
                assertTrue(recorded!!.reason.contains("parseNovelAndChapters"))
            }
        }
    }

    @Test
    fun noContractMembersFailsLoudly() {
        newRuntime().use { runtime ->
            val registry = InMemorySourceErrorRegistry()
            runBlocking {
                try {
                    load(runtime, registry, item("fixture-no-contract"), resourceText(NO_CONTRACT_DIST))
                    fail("a dist without contract members must be rejected")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.Incompatible)
                    assertTrue(e.message!!.contains("did not expose a valid plugin"))
                }
                assertTrue(registry.latest("fixture-no-contract") is SourceError.Incompatible)
            }
        }
    }

    @Test
    fun libsAesFailsLoudlyNamingLibraryAndPlugin() {
        newRuntime().use { runtime ->
            val registry = InMemorySourceErrorRegistry()
            runBlocking {
                try {
                    load(runtime, registry, item("fixture-aes"), resourceText(AES_DIST))
                    fail("a dist requiring @libs/aes must be rejected")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.UnsupportedFeature)
                    assertTrue(e.message!!.contains("@libs/aes"))
                    assertTrue(e.message!!.contains("fixture-aes"))
                }
                assertTrue(registry.latest("fixture-aes") is SourceError.UnsupportedFeature)
            }
        }
    }

    @Test
    fun unsafePluginIdFailsLoudly() {
        newRuntime().use { runtime ->
            val registry = InMemorySourceErrorRegistry()
            runBlocking {
                try {
                    // The dist argument never loads: the id gate fires first.
                    load(runtime, registry, item("../evil"), "exports.default = {};")
                    fail("an unsafe plugin id must be rejected before any load")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.Incompatible)
                    assertTrue(e.message!!.contains("not a safe plugin id"))
                }
                assertTrue(registry.latest("../evil") is SourceError.Incompatible)
            }
        }
    }

    @Test
    fun filterAndSettingsValuesRoundTripThroughStorage() {
        newRuntime().use { runtime ->
            val registry = InMemorySourceErrorRegistry()
            runBlocking {
                val source = load(runtime, registry, goodItem(), resourceText(GOOD_DIST))

                // Defaults: full list, no prefix.
                assertEquals(20, source.popularNovels(1, false, null).size)
                assertNull(source.savedFilterValuesJson())

                // Values passed on a call persist into the plugin's storage scope.
                val limited = source.popularNovels(1, false, """{"limit":"1"}""")
                assertEquals(1, limited.size)
                assertEquals("""{"limit":"1"}""", source.savedFilterValuesJson())

                // Settings values persist too and apply to the next call.
                source.saveSettingsValues("""{"prefix":"[P] "}""")
                val marked = source.popularNovels(1, false, null)
                assertEquals(1, marked.size)
                assertTrue(marked.all { it.name.startsWith("[P] ") })
                assertEquals("""{"prefix":"[P] "}""", source.savedSettingsValuesJson())
                source.close()

                // Reload: stored values re-apply into the plugin's filter
                // objects at load, with no values passed on the call.
                val reloaded = load(runtime, registry, goodItem(), resourceText(GOOD_DIST))
                val after = reloaded.popularNovels(1, false, null)
                assertEquals(1, after.size)
                assertTrue(after.all { it.name.startsWith("[P] ") })
                reloaded.close()
            }
        }
    }

    @Test
    fun statusNormalizationMapsRawValues() {
        newRuntime().use { runtime ->
            val registry = InMemorySourceErrorRegistry()
            runBlocking {
                val source = load(runtime, registry, item("fixture-status"), STATUS_DIST)
                assertEquals("Publishing Finished", source.novelDetails("canonical").status)
                assertEquals("Unknown", source.novelDetails("numeric").status)
                assertEquals("Unknown", source.novelDetails("unrecognized").status)
                assertNull(source.novelDetails("absent").status)
                source.close()
            }
        }
    }

    @Test
    fun emptyDetailsFailLoudlyAsParse() {
        newRuntime().use { runtime ->
            val registry = InMemorySourceErrorRegistry()
            runBlocking {
                val source = load(runtime, registry, item("fixture-empty-details"), EMPTY_DETAILS_DIST)
                try {
                    source.novelDetails("some-novel")
                    fail("an all-empty parseNovel result must fail loudly")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.Parse)
                    assertTrue(e.message!!.contains("returned no name and no chapters"))
                }
                assertTrue(registry.latest("fixture-empty-details") is SourceError.Parse)
                source.close()
            }
        }
    }

    @Test
    fun networkFailureMapsIntoTheRegistry() {
        newRuntime().use { runtime ->
            val registry = InMemorySourceErrorRegistry()
            runBlocking {
                val source = load(runtime, registry, goodItem(), resourceText(GOOD_DIST))
                try {
                    // Page 2 of the catalog has no recorded fixture, so the
                    // fetch fails and the adapter must classify it as network.
                    source.popularNovels(2, false, null)
                    fail("an unrecorded page must fail loudly")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.Network)
                    assertTrue(e.message!!.contains("Fetch failed for"))
                }
                assertTrue(registry.latest("fixture-v3") is SourceError.Network)
                source.close()
            }
        }
    }

    private companion object {
        const val GOOD_DIST = "pluginhost/v3-fixtures/good-dist.js"
        const val WRONG_GENERATION_DIST = "pluginhost/v3-fixtures/wrong-generation-dist.js"
        const val NO_CONTRACT_DIST = "pluginhost/v3-fixtures/no-contract-dist.js"
        const val AES_DIST = "pluginhost/v3-fixtures/aes-dist.js"

        val STATUS_DIST = """
            exports.default = {
              id: "fixture-status",
              name: "Status Fixture",
              site: "https://fixture.invalid/",
              lang: "en",
              version: "1.0.0",
              popularNovels: async function () { return []; },
              parseNovel: async function (novelPath) {
                var status;
                if (novelPath.indexOf("numeric") >= 0) {
                  status = 7;
                } else if (novelPath.indexOf("unrecognized") >= 0) {
                  status = "Hiatus";
                } else if (novelPath.indexOf("canonical") >= 0) {
                  status = "publishing finished";
                }
                return { name: "N", path: novelPath, chapters: [], status: status };
              },
              parseChapter: async function () { return "text"; },
              searchNovels: async function () { return []; },
            };
        """.trimIndent()

        val EMPTY_DETAILS_DIST = """
            exports.default = {
              id: "fixture-empty-details",
              name: "Empty Details Fixture",
              site: "https://fixture.invalid/",
              lang: "en",
              version: "1.0.0",
              popularNovels: async function () { return []; },
              parseNovel: async function (novelPath) {
                return { path: novelPath, chapters: [] };
              },
              parseChapter: async function () { return "text"; },
              searchNovels: async function () { return []; },
            };
        """.trimIndent()
    }
}

/** Replays recorded URL-to-response pairs; an unknown URL fails loudly. */
private fun recordedClient(): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(RecordedFixturesInterceptor())
    .build()

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
