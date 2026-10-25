package com.chief.anansi.ui.browse

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DistCache contract tests (spec: New tests): single-flight fetches for one
 * url, the 24-entry LRU bound, and failures that reach every awaiter without
 * being cached.
 */
class SourceSessionsDistCacheTest {

    private fun newCache() = DistCache(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    @Test
    fun concurrentLoadsShareOneFetch() = runBlocking<Unit> {
        val cache = newCache()
        val calls = AtomicInteger(0)
        coroutineScope {
            val awaiters = (1..4).map {
                async {
                    cache.getOrFetch("url-a") {
                        calls.incrementAndGet()
                        delay(150)
                        "dist-text"
                    }
                }
            }
            awaiters.forEach { assertEquals("dist-text", it.await()) }
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun cacheEvictsBeyondBoundAndRefetchesEvicted() = runBlocking<Unit> {
        val cache = newCache()
        val calls = AtomicInteger(0)
        suspend fun text(url: String): String = cache.getOrFetch(url) {
            calls.incrementAndGet()
            "text-$url"
        }
        for (index in 0 until 25) text("u$index")
        assertEquals(25, calls.get())
        text("u0")
        assertEquals("evicted entry refetches", 26, calls.get())
        text("u24")
        assertEquals("most recent entry stays cached", 26, calls.get())
    }

    @Test
    fun failedFetchIsNotCachedAndReachesEveryAwaiter() = runBlocking<Unit> {
        val cache = newCache()
        val calls = AtomicInteger(0)
        coroutineScope {
            val awaiters = (1..3).map {
                async {
                    try {
                        cache.getOrFetch("bad") {
                            calls.incrementAndGet()
                            delay(100)
                            throw IllegalStateException("boom")
                        }
                        ""
                    } catch (e: IllegalStateException) {
                        e.message ?: ""
                    }
                }
            }
            awaiters.forEach { assertTrue(it.await().contains("boom")) }
        }
        assertEquals("one fetch behind all awaiters", 1, calls.get())
        val retried = cache.getOrFetch("bad") {
            calls.incrementAndGet()
            "retried"
        }
        assertEquals("failure must not be cached", "retried", retried)
        assertEquals(2, calls.get())
    }
}
