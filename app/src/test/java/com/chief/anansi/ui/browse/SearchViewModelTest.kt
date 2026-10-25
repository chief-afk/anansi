package com.chief.anansi.ui.browse

import com.chief.anansi.data.entity.SourceEntity
import com.chief.anansi.plugin.SourceNovelItem
import com.chief.anansi.plugin.runtime.SourceRuntimeLimits
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search scheduling contract tests (spec: Verification): stale generation
 * writes dropped, `searching` derived from group statuses so the spinner
 * cannot stick, clear mid-flight, progress counts, the concurrency bound,
 * the per-source timeout's retryable failure, and the per-search source
 * snapshot. Pure JVM: gated fakes on `Dispatchers.Unconfined`; no
 * coroutines-test.
 */
class SearchViewModelTest {

    private class SourceGate(private val onCancellation: (() -> SearchPageResult)? = null) {
        val requested = AtomicInteger(0)
        private var gate = CompletableDeferred<SearchPageResult>()

        suspend fun search(entity: SourceEntity, query: String): SearchPageResult {
            requested.incrementAndGet()
            // One deferred per call: a cancelled generation's gate must not
            // answer the next generation's await.
            val mine = CompletableDeferred<SearchPageResult>()
            gate = mine
            return try {
                mine.await()
            } catch (e: CancellationException) {
                // A fake that swallows cancellation models a write already
                // past its await point: the generation stamp must drop it.
                onCancellation?.invoke() ?: throw e
            }
        }

        fun complete(vararg items: SourceNovelItem) {
            gate.complete(SearchPageResult(items = items.toList()))
        }
    }

    private fun entity(id: Long, name: String) = SourceEntity(
        id = id,
        ecosystem = "lnreader",
        sourceId = "s$id",
        identity = "lnreader_$id",
        numericId = id,
        displayName = name,
    )

    private fun newViewModel(
        targets: suspend (String?) -> List<SourceEntity>,
        runSourceSearch: suspend (SourceEntity, String) -> SearchPageResult,
        searchTimeoutMillis: Long = SourceRuntimeLimits.SEARCH_SOURCE_TIMEOUT_MILLIS,
    ): SearchViewModel = SearchViewModel(
        scopedIdentity = null,
        runSourceSearch = runSourceSearch,
        targets = targets,
        scope = CoroutineScope(Dispatchers.Unconfined),
        searchTimeoutMillis = searchTimeoutMillis,
    )

    private fun doneCount(vm: SearchViewModel) = vm.state.value.groups.count {
        it.status == SearchGroupStatus.Done || it.status == SearchGroupStatus.Failed
    }

    private fun isSearching(vm: SearchViewModel) =
        vm.state.value.groups.any { it.status == SearchGroupStatus.Searching }

    @Test
    fun staleGroupWritesAfterNewerSearchBeganAreDropped() = runBlocking<Unit> {
        val gateA = SourceGate(onCancellation = {
            SearchPageResult(items = listOf(SourceNovelItem("Stale A", "stale-a")))
        })
        val gateB = SourceGate(onCancellation = {
            throw RuntimeException("stale failure")
        })
        val gates = mapOf("lnreader_1" to gateA, "lnreader_2" to gateB)
        val vm = newViewModel(
            targets = { listOf(entity(1, "A"), entity(2, "B")) },
            runSourceSearch = { e, q -> gates.getValue(e.identity).search(e, q) },
        )
        vm.search("first")
        vm.search("second")
        // The first fan-out's fakes swallowed their cancellation and tried
        // to land generation-1 outcomes after generation 2 began.
        val groups = vm.state.value.groups
        assertEquals(SearchGroupStatus.Searching, groups[0].status)
        assertTrue(groups[0].novels.isEmpty())
        assertEquals(SearchGroupStatus.Searching, groups[1].status)
        assertEquals("", groups[1].failureKind)
        assertEquals("", groups[1].failureReason)
        assertTrue("a stale completion cannot clear the spinner", isSearching(vm))
    }

    @Test
    fun searchingDerivesFromStatusesAndOnlyRealCompletionsMoveIt() = runBlocking<Unit> {
        val gateA = SourceGate()
        val gateB = SourceGate(onCancellation = {
            SearchPageResult(items = listOf(SourceNovelItem("Stale B", "stale-b")))
        })
        val gates = mapOf("lnreader_1" to gateA, "lnreader_2" to gateB)
        val vm = newViewModel(
            targets = { listOf(entity(1, "A"), entity(2, "B")) },
            runSourceSearch = { e, q -> gates.getValue(e.identity).search(e, q) },
        )
        vm.search("query")
        assertTrue(isSearching(vm))
        gateA.complete(SourceNovelItem("Fresh A", "a"))
        assertTrue("one group still searching", isSearching(vm))
        vm.search("query2")
        // B's generation-1 fake lands a stale Done after generation 2 began;
        // the spinner stays because generation 2's groups are searching.
        assertTrue("stale completion cannot clear the spinner", isSearching(vm))
        gateB.complete(SourceNovelItem("Fresh B", "b"))
        assertTrue("last group still searching", isSearching(vm))
        gateA.complete(SourceNovelItem("Fresh A 2", "a2"))
        assertFalse("all groups settled, spinner gone for good", isSearching(vm))
    }

    @Test
    fun clearQueryMidFlightLeavesNoSpinnerAndNoLateWrites() = runBlocking<Unit> {
        val gateA = SourceGate(onCancellation = {
            SearchPageResult(items = listOf(SourceNovelItem("Late", "late")))
        })
        val vm = newViewModel(
            targets = { listOf(entity(1, "A")) },
            runSourceSearch = { e, q -> gateA.search(e, q) },
        )
        vm.search("query")
        vm.clearQuery()
        val state = vm.state.value
        assertEquals("", state.query)
        assertFalse(state.hasSearched)
        assertFalse(isSearching(vm))
        val group = state.groups.single()
        assertEquals(SearchGroupStatus.Pending, group.status)
        assertTrue(group.novels.isEmpty())
    }

    @Test
    fun progressCountsDeriveFromGroupStatuses() = runBlocking<Unit> {
        val gateA = SourceGate()
        val gateB = SourceGate()
        val gates = mapOf("lnreader_1" to gateA, "lnreader_2" to gateB)
        val vm = newViewModel(
            targets = { listOf(entity(1, "A"), entity(2, "B")) },
            runSourceSearch = { e, q -> gates.getValue(e.identity).search(e, q) },
        )
        vm.search("query")
        assertEquals(0, doneCount(vm))
        assertEquals(2, vm.state.value.groups.size)
        gateA.complete(SourceNovelItem("A", "a"))
        assertEquals(1, doneCount(vm))
        gateB.complete()
        assertEquals(2, doneCount(vm))
        assertEquals(2, vm.state.value.groups.size)
    }

    @Test
    fun fanOutHoldsTheConcurrencyBound() = runBlocking<Unit> {
        val entered = AtomicInteger(0)
        val gates = ConcurrentHashMap<String, CompletableDeferred<SearchPageResult>>()
        val vm = newViewModel(
            targets = { (1..8).map { entity(it.toLong(), "S$it") } },
            runSourceSearch = { e, _ ->
                entered.incrementAndGet()
                gates.computeIfAbsent(e.identity) { CompletableDeferred() }.await()
                SearchPageResult(items = emptyList())
            },
        )
        vm.search("query")
        assertEquals(
            "only the bounded number of source searches may be in flight",
            SourceRuntimeLimits.SEARCH_CONCURRENCY,
            entered.get(),
        )
        gates.getValue("lnreader_1").complete(SearchPageResult(items = emptyList()))
        assertEquals(
            "a released permit lets the next queued source in",
            SourceRuntimeLimits.SEARCH_CONCURRENCY + 1,
            entered.get(),
        )
    }

    @Test
    fun perSourceTimeoutLandsAsRetryableNetworkFailure() = runBlocking<Unit> {
        val vm = newViewModel(
            targets = { listOf(entity(1, "Slow")) },
            runSourceSearch = { _, _ -> CompletableDeferred<SearchPageResult>().await() },
            searchTimeoutMillis = 25,
        )
        vm.search("query")
        delay(250)
        val group = vm.state.value.groups.single()
        assertEquals(SearchGroupStatus.Failed, group.status)
        assertEquals("Network", group.failureKind)
        assertTrue(
            "reason: ${group.failureReason}",
            group.failureReason.contains("did not answer within 45 s"),
        )
    }

    @Test
    fun searchAfterSourceEnabledIncludesItsGroup() = runBlocking<Unit> {
        val enabled = mutableListOf(entity(1, "A"))
        val requestedBy = ConcurrentHashMap<String, AtomicInteger>()
        val vm = newViewModel(
            targets = { enabled.toList() },
            runSourceSearch = { e, _ ->
                requestedBy.computeIfAbsent(e.identity) { AtomicInteger(0) }.incrementAndGet()
                SearchPageResult(items = emptyList())
            },
        )
        vm.search("first")
        assertEquals(listOf("lnreader_1"), vm.state.value.groups.map { it.identity })
        enabled.add(entity(2, "B"))
        vm.search("second")
        assertEquals(
            listOf("lnreader_1", "lnreader_2"),
            vm.state.value.groups.map { it.identity },
        )
        assertEquals(1, requestedBy.getValue("lnreader_2").get())
    }
}
