package com.chief.anansi.plugin.runtime

import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Plugin-host fixture test (spec: New tests item 1): loads a scripted
 * minimal plugin, exercises the require map, the storage round trip, and
 * asserts the loud error types for @libs/aes, unsupported requires,
 * fetchProto, and empty dists.
 */
class PluginHostFixtureTest {

    private fun resourceText(name: String): String =
        javaClass.classLoader.getResource(name).readText()

    private fun newRuntime(): QuickJsSourceRuntime = QuickJsSourceRuntime(
        hostScript = resourceText("pluginhost/host.js"),
        vendorScript = resourceText("pluginhost/vendor.js"),
        fetchBridge = FetchBridge(OkHttpClient()),
    )

    @Test
    fun requireMapEndToEnd() {
        newRuntime().use { runtime ->
            runBlocking {
                val plugin = runtime.load(
                    "fixture-minimal",
                    resourceText("pluginhost/minimal-plugin.js"),
                )
                val inspection = Json.parseToJsonElement(plugin.inspect()).jsonObject
                assertEquals("fixture-minimal", inspection.getValue("id").jsonPrimitive.content)
                assertTrue(inspection.getValue("members").jsonObject.getValue("popularNovels").jsonPrimitive.content == "true")
                assertTrue(inspection.getValue("hasFilters").jsonPrimitive.content == "true")

                val popular = Json.parseToJsonElement(
                    plugin.call("popularNovels", """[1, {"showLatestNovels": false}]"""),
                ).jsonArray
                assertEquals(1, popular.size)
                val novel = popular[0].jsonObject
                assertEquals("One", novel.getValue("name").jsonPrimitive.content)
                assertEquals("n/one.html", novel.getValue("path").jsonPrimitive.content)

                val chapter = Json.parseToJsonElement(
                    plugin.call("parseChapter", """["c1"]"""),
                ).jsonPrimitive.content
                assertEquals("2026/01/15", chapter)

                val probe = Json.parseToJsonElement(plugin.call("probe", "[]")).jsonObject
                assertText(probe, "filterTypeValue", "hot")
                assertText(probe, "dayjsFormat", "2026/01/15")
                assertText(probe, "utf8RoundTrip", "h\u00e9llo")
                assertText(probe, "urlencodeEncode", "a%20b")
                assertText(probe, "urlencodeDecode", "a b")
                assertTrue(probe.getValue("absoluteUrl").jsonPrimitive.content == "true")
                assertTrue(probe.getValue("relativeUrl").jsonPrimitive.content == "false")
                assertText(probe, "novelStatus", "Ongoing")
                assertText(probe, "filterType", "Picker")
                assertText(probe, "coverPrefix", "https://")
                assertText(probe, "constantsStatus", "Completed")
                assertText(probe, "htmlparser2Text", "hi")
                assertTrue(probe.getValue("fetchApiPresent").jsonPrimitive.content == "true")
                assertTrue(probe.getValue("fetchProtoPresent").jsonPrimitive.content == "true")

                plugin.close()
            }
        }
    }

    @Test
    fun storageRoundTripPersistsAcrossCalls() {
        newRuntime().use { runtime ->
            runBlocking {
                val plugin = runtime.load(
                    "fixture-minimal",
                    resourceText("pluginhost/minimal-plugin.js"),
                )
                // parseChapter stores the stamp; the next call reads it back
                // through the Kotlin-side storage scope.
                Json.parseToJsonElement(plugin.call("parseChapter", """["c1"]"""))
                val stored = Json.parseToJsonElement(
                    plugin.call("readStoredStamp", "[]"),
                ).jsonPrimitive.content
                assertEquals("2026/01/15", stored)
                plugin.close()
            }
        }
    }

    @Test
    fun libsAesFailsLoudlyNamingPlugin() {
        newRuntime().use { runtime ->
            runBlocking {
                try {
                    runtime.load(
                        "fixture-aes",
                        "const aes = require(\"@libs/aes\"); exports.default = { popularNovels: async function () { return []; } };",
                    )
                    fail("@libs/aes load must throw")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.UnsupportedFeature)
                    assertTrue(e.message!!.contains("@libs/aes"))
                    assertTrue(e.message!!.contains("fixture-aes"))
                }
            }
        }
    }

    @Test
    fun unsupportedRequireFailsLoudly() {
        newRuntime().use { runtime ->
            runBlocking {
                try {
                    runtime.load(
                        "fixture-req",
                        "require(\"left-pad\"); exports.default = { popularNovels: async function () { return []; } };",
                    )
                    fail("unsupported require must throw")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.UnsupportedFeature)
                    assertTrue(e.message!!.contains("Unsupported plugin library: left-pad"))
                }
            }
        }
    }

    @Test
    fun fetchProtoFailsLoudlyAsUnsupportedFeature() {
        newRuntime().use { runtime ->
            runBlocking {
                val plugin = runtime.load(
                    "fixture-proto",
                    "const fetchLib = require(\"@libs/fetch\"); " +
                        "exports.default = { popularNovels: async function () { return []; }, " +
                        "probe: async function () { return String(await fetchLib.fetchProto({}, \"https://fixture.invalid/\")); } };",
                )
                try {
                    plugin.call("probe", "[]")
                    fail("fetchProto must throw")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.UnsupportedFeature)
                    assertTrue(e.message!!.contains("fetchProto"))
                }
                plugin.close()
            }
        }
    }

    @Test
    fun emptyDistFailsAsIncompatible() {
        newRuntime().use { runtime ->
            runBlocking {
                try {
                    runtime.load("fixture-blank", "   ")
                    fail("empty dist must throw")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.Incompatible)
                    assertTrue(e.message!!.contains("dist is empty"))
                }
            }
        }
    }

    @Test
    fun distWithoutContractMembersFailsAsIncompatible() {
        newRuntime().use { runtime ->
            runBlocking {
                try {
                    runtime.load("fixture-empty-object", "exports.default = { id: \"x\" };")
                    fail("plugin object without contract members must throw")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.Incompatible)
                    assertTrue(e.message!!.contains("did not expose a valid plugin"))
                }
            }
        }
    }

    private fun assertText(obj: JsonObject, key: String, expected: String) {
        assertEquals(expected, obj.getValue(key).jsonPrimitive.content)
        assertFalse(expected.isEmpty() && obj.getValue(key).jsonPrimitive.content.isEmpty())
    }
}
