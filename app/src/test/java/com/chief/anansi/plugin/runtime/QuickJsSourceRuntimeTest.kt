package com.chief.anansi.plugin.runtime

import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Runtime contract tests (spec: New tests): the real JVM QuickJs engine
 * drives the memory-abort mapping, the wall-clock call deadline, the slot
 * LRU bound, same-dist reload reuse, and storage-scope survival.
 */
class QuickJsSourceRuntimeTest {

    private fun resourceText(name: String): String =
        javaClass.classLoader.getResource(name).readText()

    private fun newRuntime(
        callDeadlineMillis: Long = SourceRuntimeLimits.CALL_DEADLINE_MILLIS,
        slotMemoryLimitBytes: Long = SourceRuntimeLimits.SLOT_MEMORY_LIMIT_BYTES,
    ): QuickJsSourceRuntime = QuickJsSourceRuntime(
        hostScript = resourceText("pluginhost/host.js"),
        vendorScript = resourceText("pluginhost/vendor.js"),
        fetchBridge = FetchBridge(OkHttpClient()),
        callDeadlineMillis = callDeadlineMillis,
        slotMemoryLimitBytes = slotMemoryLimitBytes,
    )

    private fun tinyPlugin(extraMembers: String = ""): String =
        "exports.default = { popularNovels: async function () { return []; }$extraMembers };"

    @Test
    fun memoryLimitAbortMapsToUnsupportedFeatureNamingPluginAndLimit() {
        newRuntime(slotMemoryLimitBytes = 16L * 1024 * 1024).use { runtime ->
            runBlocking {
                val plugin = runtime.load(
                    "oom-plugin",
                    tinyPlugin(
                        ", probe: async function () {" +
                            " const chunks = [];" +
                            " while (true) { chunks.push(new Array(2 * 1024 * 1024).fill(\"a\")); }" +
                            " return \"never\"; }",
                    ),
                )
                try {
                    plugin.call("probe", "[]")
                    fail("allocation beyond the slot limit must fail")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.UnsupportedFeature)
                    assertTrue("plugin id in: ${e.message}", e.message!!.contains("oom-plugin"))
                    assertTrue(
                        "limit in: ${e.message}",
                        e.message!!.contains("16 MB JavaScript memory limit"),
                    )
                }
            }
        }
    }

    @Test
    fun neverSettlingCallFailsWithinInjectedDeadlineAsNetwork() {
        newRuntime(callDeadlineMillis = 250).use { runtime ->
            runBlocking {
                val plugin = runtime.load(
                    "stall-plugin",
                    tinyPlugin(
                        ", probe: async function () { return new Promise(function () {}); }",
                    ),
                )
                val startedAt = System.currentTimeMillis()
                try {
                    plugin.call("probe", "[]")
                    fail("a never-settling promise must hit the call deadline")
                } catch (e: SourcePluginException) {
                    assertTrue(e.error is SourceError.Network)
                    assertTrue(e.message!!.contains("did not answer within 250 ms"))
                    assertTrue(
                        "deadline fired late: ${System.currentTimeMillis() - startedAt} ms",
                        System.currentTimeMillis() - startedAt < 15_000,
                    )
                }
            }
        }
    }

    @Test
    fun lruEvictionClosesLeastRecentlyUsedSlotOnly() {
        newRuntime().use { runtime ->
            runBlocking {
                val dist = tinyPlugin()
                val handles = (0 until 9).associate { index ->
                    "p$index" to runtime.load("p$index", dist)
                }
                val eldest = handles.getValue("p0")
                try {
                    eldest.inspect()
                    fail("the least-recently-used slot must be closed after eviction")
                } catch (e: IllegalStateException) {
                    assertTrue(e.message!!.contains("runtime slot is closed"))
                }
                handles.getValue("p1").inspect()
                handles.getValue("p8").inspect()
            }
        }
    }

    @Test
    fun sameIdSameDistReloadReturnsSameLiveSlotInstance() {
        newRuntime().use { runtime ->
            runBlocking {
                val dist = tinyPlugin()
                val first = runtime.load("dupe", dist)
                val second = runtime.load("dupe", dist)
                assertSame(first, second)
                assertTrue(first.inspect().contains("\"pluginId\":\"dupe\""))
            }
        }
    }

    @Test
    fun storageScopeSurvivesEvictionAndReload() {
        newRuntime().use { runtime ->
            runBlocking {
                val dist = resourceText("pluginhost/minimal-plugin.js")
                val original = runtime.load("store-a", dist)
                original.call("parseChapter", """["c1"]""")
                repeat(8) { index -> runtime.load("filler-$index", tinyPlugin()) }
                val reloaded = runtime.load("store-a", dist)
                assertNotSame(original, reloaded)
                val stamp = reloaded.call("readStoredStamp", "[]")
                assertTrue(stamp.contains("2026/01/15"))
            }
        }
    }
}
