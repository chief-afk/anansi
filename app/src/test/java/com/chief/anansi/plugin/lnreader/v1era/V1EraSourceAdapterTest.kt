package com.chief.anansi.plugin.lnreader.v1era

import com.chief.anansi.plugin.error.InMemorySourceErrorRegistry
import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import com.chief.anansi.plugin.runtime.FetchBridge
import com.chief.anansi.plugin.runtime.QuickJsSourceRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
 * v1-era adapter fixture tests (spec: New tests items 1-4): the happy path
 * through recorded HTTP fixtures, the loud generation checks, the loud
 * envelope and shape violations, and the filters passthrough with values
 * riding the popularNovels options argument.
 */
class V1EraSourceAdapterTest {

    private fun resourceText(name: String): String =
        javaClass.classLoader.getResource(name).readText()

    private fun newRuntime(): QuickJsSourceRuntime = QuickJsSourceRuntime(
        hostScript = resourceText("pluginhost/host.js"),
        vendorScript = resourceText("pluginhost/vendor.js"),
        fetchBridge = FetchBridge(recordedClient()),
    )

    private fun load(
        runtime: QuickJsSourceRuntime,
        registry: InMemorySourceErrorRegistry,
        dist: String,
        pluginId: String = "fixture-v1era",
    ): V1EraSource = runBlocking {
        V1EraPluginLoader.load(
            runtime = runtime,
            registry = registry,
            sourceId = 42L,
            pluginId = pluginId,
            dist = dist,
            name = "Fixture V1 Era",
            lang = "en",
            iconUrl = "https://libread.com/favicon.png",
        )
    }

    @Test
    fun happyPathMappingsThroughRecordedFixtures() {
        newRuntime().use { runtime ->
            val registry = InMemorySourceErrorRegistry()
            runBlocking {
                val source = load(runtime, registry, resourceText(GOOD_DIST))

                // The load outcome carries the generation verdict for
                // The registration probe.
                assertEquals("fixture-v1era", source.pluginId)
                assertEquals(42L, source.id)
                assertEquals("lnreader", source.ecosystem)
                assertEquals("v1era", source.generation)
                assertEquals("Fixture V1 Era", source.name)
                assertEquals("en", source.lang)

                // popularNovels unwraps the { novels } envelope.
                val popular = source.popularNovels(1, showLatestNovels = false, filterValuesJson = null)
                assertEquals(20, popular.size)
                assertEquals("Martial World", popular[0].name)
                assertEquals("https://libread.com/libread/martial-world-novel-137346", popular[0].path)
                assertTrue(popular[0].cover!!.startsWith("https://libread.com/"))

                // showLatestNovels rides the options argument too.
                val latest = source.popularNovels(1, showLatestNovels = true, filterValuesJson = null)
                assertEquals(20, latest.size)
                assertEquals("Getting \$10 Million From My First Sign-In", latest[0].name)

                // The filters array schema passes through as plugin JSON.
                val filters = Json.parseToJsonElement(source.filters.toString()).jsonArray
                assertEquals(1, filters.size)
                val sort = filters[0].jsonObject
                assertEquals("sort", sort.getValue("key").jsonPrimitive.content)
                assertEquals(1, sort.getValue("inputType").jsonPrimitive.content.toInt())
                assertEquals(2, sort.getValue("values").jsonArray.size)
                assertNull(source.settings)

                // novelDetails via parseNovelAndChapters returns metadata
                // plus the chapter list.
                val details = source.novelDetails("https://libread.com/libread/martial-world-novel-137346")
                assertEquals("Martial World", details.name)
                assertTrue(details.author!!.contains("Cocooned Cow"))
                assertTrue(details.genres!!.contains("Action"))
                assertEquals("Completed", details.status)
                assertTrue(details.summary!!.contains("Realm of the Gods"))
                assertTrue(details.cover!!.startsWith("https://libread.com/"))
                assertEquals(40, details.chapters.size)
                assertEquals("Prologue: Magic Cube", details.chapters[0].name)
                assertEquals(
                    "https://libread.com/libread/martial-world-novel-137346/chapter-01",
                    details.chapters[0].path,
                )

                // chapterText passes both paths through: the fixture reads
                // the chapter URL from the second argument, so a wrong
                // argument order would fail the fetch loudly. Chapter HTML
                // is normalized to plain text at the adapter boundary.
                val text = source.chapterText(
                    novelPath = "https://libread.com/libread/martial-world-novel-137346",
                    chapterPath = "https://libread.com/libread/martial-world-novel-137346/chapter-01",
                )
                assertTrue(text.length > 1000)
                assertTrue(text.contains("Snowfall Realm"))
                assertTrue(!text.contains("<p>"))
                assertTrue(!text.contains("<div"))
                assertTrue(text.contains("\n\n"))

                // searchNovels is called with the search term only and its
                // bare-array result passes through.
                val search = source.searchNovels("martial", 1, filterValuesJson = null)
                assertEquals(50, search.size)
                assertEquals(
                    "Martial Dao: Starting from the Village Fungus God",
                    search[0].name,
                )

                assertNull(registry.latest("fixture-v1era"))
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
                    // The v3 fixture dist (parseNovel only) is the
                    // wrong generation for this adapter.
                    load(runtime, registry, resourceText(V3_GOOD_DIST))
                    fail("a v3 dist must be rejected by the v1-era adapter")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.Incompatible)
                    assertTrue(e.message!!.contains("v3 plugin loaded by the v1-era adapter"))
                }
                val recorded = registry.latest("fixture-v1era")
                assertTrue(recorded is SourceError.Incompatible)
                assertTrue(recorded!!.reason.contains("parseNovel"))
            }
        }
    }

    @Test
    fun noContractMembersFailsLoudly() {
        newRuntime().use { runtime ->
            val registry = InMemorySourceErrorRegistry()
            runBlocking {
                try {
                    load(
                        runtime,
                        registry,
                        resourceText(NO_CONTRACT_DIST),
                        pluginId = "fixture-v1era-no-contract",
                    )
                    fail("a dist without parse members must be rejected")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.Incompatible)
                    assertTrue(e.message!!.contains("not a v1-era plugin"))
                }
                val recorded = registry.latest("fixture-v1era-no-contract")
                assertTrue(recorded is SourceError.Incompatible)
            }
        }
    }

    @Test
    fun missingPopularNovelsEnvelopeFailsLoudly() {
        newRuntime().use { runtime ->
            val registry = InMemorySourceErrorRegistry()
            runBlocking {
                val source = load(runtime, registry, BARE_POPULAR_DIST, pluginId = "fixture-bare-popular")
                try {
                    source.popularNovels(1, showLatestNovels = false, filterValuesJson = null)
                    fail("a bare-array popularNovels result must fail loudly")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.Parse)
                    assertTrue(e.message!!.contains("envelope"))
                }
                assertTrue(registry.latest("fixture-bare-popular") is SourceError.Parse)
                source.close()
            }
        }
    }

    @Test
    fun objectSearchResultFailsLoudly() {
        newRuntime().use { runtime ->
            val registry = InMemorySourceErrorRegistry()
            runBlocking {
                val source = load(runtime, registry, OBJECT_SEARCH_DIST, pluginId = "fixture-object-search")
                try {
                    source.searchNovels("martial", 1, filterValuesJson = null)
                    fail("an object searchNovels result must fail loudly")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.Parse)
                    assertTrue(e.message!!.contains("did not return a list"))
                }
                assertTrue(registry.latest("fixture-object-search") is SourceError.Parse)
                source.close()
            }
        }
    }

    @Test
    fun chapterTextShapeDeviationsFailLoudly() {
        newRuntime().use { runtime ->
            val registry = InMemorySourceErrorRegistry()
            runBlocking {
                val bare = load(runtime, registry, BARE_CHAPTER_DIST, pluginId = "fixture-bare-chapter")
                try {
                    bare.chapterText("n", "c")
                    fail("a bare-string parseChapter result must fail loudly")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.Parse)
                    assertTrue(e.message!!.contains("did not return a SourceChapter object"))
                }
                assertTrue(registry.latest("fixture-bare-chapter") is SourceError.Parse)
                bare.close()

                val missing = load(runtime, registry, MISSING_TEXT_DIST, pluginId = "fixture-missing-text")
                try {
                    missing.chapterText("n", "c")
                    fail("a SourceChapter without chapterText must fail loudly")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.Parse)
                    assertTrue(e.message!!.contains("no string chapterText"))
                }
                assertTrue(registry.latest("fixture-missing-text") is SourceError.Parse)
                missing.close()
            }
        }
    }

    @Test
    fun filterValuesPersistAndRideTheOptionsArgument() {
        newRuntime().use { runtime ->
            val registry = InMemorySourceErrorRegistry()
            runBlocking {
                val source = load(runtime, registry, resourceText(GOOD_DIST))

                // Defaults: the completed list, nothing stored.
                assertEquals("Martial World", source.popularNovels(1, false, null)[0].name)
                assertNull(source.savedFilterValuesJson())

                // Values passed on a call persist into the plugin's storage
                // scope and ride the popularNovels options argument.
                val latest = source.popularNovels(1, false, """{"sort":"latest"}""")
                assertEquals("Getting \$10 Million From My First Sign-In", latest[0].name)
                assertEquals("""{"sort":"latest"}""", source.savedFilterValuesJson())

                // A later call with no explicit values still rides the
                // stored document.
                assertEquals(
                    "Getting \$10 Million From My First Sign-In",
                    source.popularNovels(1, false, null)[0].name,
                )

                // Switching back via explicit values.
                assertEquals("Martial World", source.popularNovels(1, false, """{"sort":"completed"}""")[0].name)
                assertEquals("""{"sort":"completed"}""", source.savedFilterValuesJson())
                source.close()

                // Reload: the stored values apply to the next catalog call
                // with nothing passed in.
                val reloaded = load(runtime, registry, resourceText(GOOD_DIST))
                assertEquals("""{"sort":"completed"}""", reloaded.savedFilterValuesJson())
                assertEquals("Martial World", reloaded.popularNovels(1, false, null)[0].name)
                reloaded.close()
            }
        }
    }

    private companion object {
        const val GOOD_DIST = "pluginhost/v1era-fixtures/good-dist.js"
        const val NO_CONTRACT_DIST = "pluginhost/v1era-fixtures/no-contract-dist.js"
        const val V3_GOOD_DIST = "pluginhost/v3-fixtures/good-dist.js"

        // v1era-shaped module whose popularNovels returns a bare array,
        // skipping the { novels } envelope (spec: loud envelope violations).
        val BARE_POPULAR_DIST = """
            exports.default = {
              popularNovels: async function () { return []; },
              parseNovelAndChapters: async function () { return {}; },
              parseChapter: async function () { return { chapterText: "t" }; },
              searchNovels: async function () { return []; },
            };
        """.trimIndent()

        // v1era-shaped module whose searchNovels returns an object instead
        // of the bare array this generation must return.
        val OBJECT_SEARCH_DIST = """
            exports.default = {
              popularNovels: async function () { return { novels: [] }; },
              parseNovelAndChapters: async function () { return {}; },
              parseChapter: async function () { return { chapterText: "t" }; },
              searchNovels: async function () { return { novels: [] }; },
            };
        """.trimIndent()

        // parseChapter returning a bare string: the v1-era shape deviation
        // the adapter must never silently unwrap.
        val BARE_CHAPTER_DIST = """
            exports.default = {
              popularNovels: async function () { return { novels: [] }; },
              parseNovelAndChapters: async function () { return {}; },
              parseChapter: async function () { return "chapter body"; },
              searchNovels: async function () { return []; },
            };
        """.trimIndent()

        // parseChapter returning a SourceChapter object without chapterText.
        val MISSING_TEXT_DIST = """
            exports.default = {
              popularNovels: async function () { return { novels: [] }; },
              parseNovelAndChapters: async function () { return {}; },
              parseChapter: async function (novelUrl, chapterUrl) {
                return { sourceId: 13, novelUrl: novelUrl, chapterUrl: chapterUrl,
                  chapterName: "Chapter" };
              },
              searchNovels: async function () { return []; },
            };
        """.trimIndent()
    }
}

/** Replays recorded URL-to-response pairs; an unknown URL fails loudly. */
private fun recordedClient(): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(V1EraRecordedFixturesInterceptor())
    .build()

private class V1EraRecordedFixturesInterceptor : Interceptor {

    private val fixtures: Map<String, RecordedResponse> = loadFixtures()

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url.toString()
        val fixture = fixtures[url]
            ?: throw IllegalStateException("No recorded fixture for $url")
        val body = V1EraRecordedFixturesInterceptor::class.java
            .classLoader.getResource("pluginhost/v1era-recorded/" + fixture.file)
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
            V1EraRecordedFixturesInterceptor::class.java
                .classLoader.getResource("pluginhost/v1era-recorded/fixtures.json").readText(),
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
