package com.chief.anansi.plugin.lnreader.v3

import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Manifest parsing fixture tests (spec: New tests item 1): a valid item
 * parses to the seven required fields (plus optional customCSS), malformed
 * items report concrete per-entry errors, and a manifest whose every entry
 * is malformed is a hard error. Also covers the dist url resolution rule.
 */
class V3ManifestTest {

    private val goodItem = """
        {
          "id": "novelfull",
          "name": "NovelFull",
          "site": "https://novelfull.com/",
          "lang": "en",
          "version": "2.2.1",
          "url": "english/NovelFull[readnovelfull].js",
          "iconUrl": "https://novelfull.com/icon.png"
        }
    """.trimIndent()

    private fun parse(vararg items: String): V3ManifestListing =
        V3Manifest.parse(items.joinToString(",", prefix = "[", postfix = "]"))

    @Test
    fun validItemParsesToSevenFields() = runBlocking {
        val listing = V3Manifest.parse("[$goodItem]")
        assertEquals(1, listing.items.size)
        assertEquals(0, listing.entryErrors.size)
        val item = listing.items.single()
        assertEquals("novelfull", item.id)
        assertEquals("NovelFull", item.name)
        assertEquals("https://novelfull.com/", item.site)
        assertEquals("en", item.lang)
        assertEquals("2.2.1", item.version)
        assertEquals("english/NovelFull[readnovelfull].js", item.url)
        assertEquals("https://novelfull.com/icon.png", item.iconUrl)
        assertNull(item.customCSS)
    }

    @Test
    fun customCSSIsCarriedWhenPresent() = runBlocking {
        val item = goodItem.replace(
            "\"iconUrl\": \"https://novelfull.com/icon.png\"",
            "\"iconUrl\": \"https://novelfull.com/icon.png\", \"customCSS\": \"body{}\"",
        )
        assertEquals("body{}", V3Manifest.parse("[$item]").items.single().customCSS)
    }

    @Test
    fun missingFieldReportsConcreteEntryError() = runBlocking {
        val broken = goodItem.replace(
            "\"site\": \"https://novelfull.com/\",",
            "",
        )
        val listing = parse(goodItem, broken)
        assertEquals(1, listing.items.size)
        val error = listing.entryErrors.single()
        assertEquals(1, error.index)
        assertEquals("Plugin manifest item \"1\" is missing \"site\"", error.reason)
    }

    @Test
    fun nonStringFieldReportsConcreteEntryError() = runBlocking {
        val broken = goodItem.replace("\"version\": \"2.2.1\"", "\"version\": 3")
        val listing = parse(goodItem, broken)
        val error = listing.entryErrors.single()
        assertEquals(1, error.index)
        assertEquals(
            "Plugin manifest item \"1\" field \"version\" is not a string",
            error.reason,
        )
    }

    @Test
    fun nonObjectItemIsMalformed() = runBlocking {
        val listing = parse(goodItem, "\"just-a-string\"")
        val error = listing.entryErrors.single()
        assertEquals(1, error.index)
        assertEquals("Plugin manifest item \"1\" is malformed", error.reason)
    }

    @Test
    fun validEntriesSurviveMalformedOnes() = runBlocking {
        val broken = javaClass.classLoader
            .getResource("pluginhost/v3-fixtures/manifest-broken.json")
            .readText()
        val listing = V3Manifest.parse(broken)
        assertEquals(listOf("fixture-v3"), listing.items.map { it.id })
        assertEquals(listOf(0, 1, 2), listing.entryErrors.map { it.index })
        assertEquals(
            "Plugin manifest item \"0\" is missing \"site\"",
            listing.entryErrors[0].reason,
        )
        assertEquals(
            "Plugin manifest item \"1\" field \"version\" is not a string",
            listing.entryErrors[1].reason,
        )
        assertEquals("Plugin manifest item \"2\" is malformed", listing.entryErrors[2].reason)
    }

    @Test
    fun everyEntryMalformedIsAHardError() = runBlocking {
        val broken = goodItem.replace("\"site\": \"https://novelfull.com/\",", "")
        try {
            parse(broken, "\"also-broken\"")
            fail("an all-malformed manifest must be a hard error")
        } catch (e: SourcePluginException) {
            assertTrue(e.error is SourceError.Parse)
            assertEquals("Plugin manifest item \"0\" is missing \"site\"", e.message)
        }
    }

    @Test
    fun nonArrayManifestIsAHardError() = runBlocking {
        try {
            V3Manifest.parse("{\"id\": \"novelfull\"}")
            fail("a non-array manifest must be a hard error")
        } catch (e: SourcePluginException) {
            assertTrue(e.error is SourceError.Parse)
            assertEquals("Plugin manifest has an unexpected format", e.message)
        }
    }

    @Test
    fun emptyManifestParsesToEmptyListing() = runBlocking {
        val listing = V3Manifest.parse("[]")
        assertEquals(0, listing.items.size)
        assertEquals(0, listing.entryErrors.size)
    }

    @Test
    fun relativeDistUrlResolvesIntoThePluginsTree() {
        val resolved = V3Manifest.resolveDistUrl(
            "english/NovelFull[readnovelfull].js",
            "https://example.test/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json",
        )
        assertEquals(
            "https://example.test/LNReader/lnreader-plugins/plugins/v3.0.0/.js/src/plugins/" +
                "english/NovelFull[readnovelfull].js",
            resolved,
        )
    }

    @Test
    fun absoluteDistUrlIsUsedAsIs() {
        val resolved = V3Manifest.resolveDistUrl(
            "https://cdn.test/dist/plugin.js",
            "https://example.test/.dist/plugins.min.json",
        )
        assertEquals("https://cdn.test/dist/plugin.js", resolved)
    }

    @Test
    fun protocolRelativeDistUrlIsUsedAsIs() {
        val resolved = V3Manifest.resolveDistUrl(
            "//cdn.test/dist/plugin.js",
            "https://example.test/.dist/plugins.min.json",
        )
        assertEquals("//cdn.test/dist/plugin.js", resolved)
    }

    @Test
    fun missingDistUrlFailsLoudly() {
        try {
            V3Manifest.resolveDistUrl("  ", "https://example.test/.dist/plugins.min.json")
            fail("a missing dist url must fail")
        } catch (e: SourcePluginException) {
            assertTrue(e.error is SourceError.Parse)
            assertEquals("Plugin dist url is missing", e.message)
        }
    }
}
