package com.chief.anansi.plugin.repo

import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import com.chief.anansi.plugin.runtime.FetchBridge
import com.chief.anansi.plugin.runtime.QuickJsSourceRuntime
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Generation probe fixture tests (spec: Verification): a module.exports-
 * shaped v1 dist probes as V1ERA now that the probe appends the loader's
 * hoist stanza (the F32 case), an exports.default v3 dist still probes as
 * V3, and a dist with neither discriminator stays Incompatible.
 */
class GenerationProbeTest {

    private fun resourceText(name: String): String =
        javaClass.classLoader.getResource(name).readText()

    private fun probe(pluginId: String, dist: String): String {
        QuickJsSourceRuntime(
            hostScript = resourceText("pluginhost/host.js"),
            vendorScript = resourceText("pluginhost/vendor.js"),
            fetchBridge = FetchBridge(OkHttpClient()),
        ).use { runtime ->
            return runBlocking {
                GenerationProbe(runtime).probe(pluginId, dist)
            }
        }
    }

    private val v1ModuleExportsDist =
        "module.exports = { " +
            "popularNovels: async function () { return []; }, " +
            "parseNovelAndChapters: async function () { return []; }, " +
            "parseChapter: async function () { return {}; }, " +
            "searchNovels: async function () { return []; } };"

    private val v3Dist =
        "exports.default = { " +
            "popularNovels: async function () { return []; }, " +
            "parseNovel: async function () { return []; } };"

    private val neitherDist =
        "exports.default = { popularNovels: async function () { return []; } };"

    @Test
    fun moduleExportsShapedV1DistProbesAsV1Era() {
        assertEquals(
            SourceGenerations.V1ERA,
            probe("fixture-v1era", v1ModuleExportsDist),
        )
    }

    @Test
    fun exportsDefaultV3DistStillProbesAsV3() {
        assertEquals(SourceGenerations.V3, probe("fixture-v3", v3Dist))
    }

    @Test
    fun distWithNeitherDiscriminatorStaysIncompatible() {
        try {
            probe("fixture-neither", neitherDist)
            fail("a dist with neither discriminator must stay Incompatible")
        } catch (e: SourcePluginException) {
            assertTrue(e.error is SourceError.Incompatible)
            assertTrue(e.message!!.contains("neither a v3 nor a v1-era"))
        }
    }
}
