package com.chief.anansi.plugin.lnreader.v3

import com.chief.anansi.plugin.JsPlugin
import com.chief.anansi.plugin.JsPluginCallException
import com.chief.anansi.plugin.error.InMemorySourceErrorRegistry
import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Classifier tests (spec: Verification): the envelope origin and message
 * decide the kind, raw URLs never survive into a display reason, the
 * private-range block passes through verbatim, and blocks carry the exact
 * posture copy. Authoritative runtime-thrown [SourcePluginException]s pass
 * through the adapter unmodified.
 */
class ClassifyCallFailureTest {

    private val registry = InMemorySourceErrorRegistry()

    /** The posture copy the spec fixes verbatim; pinned here, not imported. */
    private val blockedCopy =
        "This source needs a browser check Anansi can't run. It stays broken until the plugin adapts."

    @Test
    fun fetchBridgeFailureHumanizesToHostAndPlainReason() {
        val error = classifyCallFailure(
            JsPluginCallException(
                "Fetch failed for https://free.kolnovel.com/api/chapters?page=2: timeout",
                origin = "fetch-bridge",
            ),
        )
        assertTrue(error is SourceError.Network)
        assertEquals("free.kolnovel.com did not respond in time", error.reason)
        assertFalse(error.reason.contains("http"))
        assertFalse(error.reason.contains("?page=2"))
    }

    @Test
    fun laterUrlsInAFetchMessageAlsoLoseSchemeAndPath() {
        val error = classifyCallFailure(
            JsPluginCallException(
                "Fetch failed for https://site.example/a?b=1: No recorded fixture for https://site.example/a?b=1",
                origin = "fetch-bridge",
            ),
        )
        assertTrue(error is SourceError.Network)
        assertEquals("site.example No recorded fixture for site.example", error.reason)
    }

    @Test
    fun fetchBridgeFailureWithoutUrlKeepsStrippedMessage() {
        val error = classifyCallFailure(
            JsPluginCallException("Response body exceeded the 2 MB fetch cap", origin = "fetch-bridge"),
        )
        assertTrue(error is SourceError.Network)
        assertEquals("Response body exceeded the 2 MB fetch cap", error.reason)
    }

    @Test
    fun blockedPrivateTargetPassesThroughVerbatim() {
        val message = "Blocked fetch target: 192.168.1.10 is a private or local address"
        val error = classifyCallFailure(JsPluginCallException(message, origin = "fetch-bridge"))
        assertTrue(error is SourceError.Blocked)
        assertEquals(message, error.reason)
    }

    @Test
    fun couldNotReach403CarriesTheBlockedPostureCopy() {
        val error = classifyCallFailure(JsPluginCallException("Could not reach site (403)"))
        assertTrue(error is SourceError.Blocked)
        assertEquals(blockedCopy, error.reason)
    }

    @Test
    fun challengeWordingCarriesTheBlockedPostureCopy() {
        val messages = listOf(
            "Cloudflare check required before the content loads",
            "please solve the CAPTCHA",
            "waiting for challenge to finish",
            "This site needs a WEBVIEW to continue",
        )
        messages.forEach { message ->
            val error = classifyCallFailure(JsPluginCallException(message))
            assertTrue("expected Blocked for: $message", error is SourceError.Blocked)
            assertEquals(blockedCopy, error.reason)
        }
    }

    @Test
    fun couldNotReachOtherStatusesStayNetwork() {
        val error = classifyCallFailure(JsPluginCallException("Could not reach site (500)"))
        assertTrue(error is SourceError.Network)
        assertEquals("Could not reach site (500)", error.reason)
    }

    @Test
    fun pluginThrownContentShapeFailuresStayParse() {
        val error = classifyCallFailure(
            JsPluginCallException("Plugin \"x\" parseNovel did not return an object"),
        )
        assertTrue(error is SourceError.Parse)
        assertEquals("Plugin \"x\" parseNovel did not return an object", error.reason)
    }

    @Test
    fun unsupportedFeatureMarkerMapsToUnsupportedFeature() {
        val error = classifyCallFailure(
            JsPluginCallException("anansi/unsupported-feature: FormData is not supported"),
        )
        assertTrue(error is SourceError.UnsupportedFeature)
        assertEquals("FormData is not supported", error.reason)
    }

    @Test
    fun runtimeThrownSourceMissingPassesThroughTheAdapter() = runBlocking {
        val authoritative = SourcePluginException(
            SourceError.SourceMissing("Plugin \"fixture-authoritative\" is not installed"),
        )
        val source = V3Source(
            plugin = pluginThrowing(authoritative),
            registry = registry,
            inspected = V3Inspection(emptySet(), null, null, null),
            manifestItem = item("fixture-authoritative"),
            id = 1L,
        )
        try {
            source.popularNovels(1, showLatestNovels = false, filterValuesJson = null)
            fail("an authoritative runtime failure must propagate")
        } catch (e: SourcePluginException) {
            assertSame(authoritative, e)
            assertEquals(authoritative.error, registry.latest("fixture-authoritative"))
        }
    }

    @Test
    fun runtimeThrownMemoryLimitPassesThroughTheAdapter() = runBlocking {
        val authoritative = SourcePluginException(
            SourceError.UnsupportedFeature(
                "Plugin \"fixture-authoritative\" exceeded its 16 MB JavaScript memory limit",
            ),
        )
        val source = V3Source(
            plugin = pluginThrowing(authoritative),
            registry = registry,
            inspected = V3Inspection(emptySet(), null, null, null),
            manifestItem = item("fixture-authoritative"),
            id = 1L,
        )
        try {
            source.popularNovels(1, showLatestNovels = false, filterValuesJson = null)
            fail("an authoritative runtime failure must propagate")
        } catch (e: SourcePluginException) {
            assertSame(authoritative, e)
            assertEquals(authoritative.error, registry.latest("fixture-authoritative"))
        }
    }

    private fun pluginThrowing(error: SourcePluginException): JsPlugin = object : JsPlugin {
        override val pluginId: String = "fixture-authoritative"

        override suspend fun inspect(): String = "{}"

        override suspend fun call(method: String, argsJson: String): String = throw error

        override suspend fun close() {}
    }

    private fun item(id: String) = V3ManifestItem(
        id = id,
        name = "Fixture",
        site = "https://fixture.invalid/",
        lang = "en",
        version = "1.0.0",
        url = "$id.js",
        iconUrl = "https://fixture.invalid/icon.png",
    )
}
