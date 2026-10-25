package com.chief.anansi.ui.browse

import com.chief.anansi.data.entity.SourceEntity
import com.chief.anansi.plugin.SourceNovelItem
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog single-flight contract tests (spec: Verification): a load-more
 * during an in-flight page is ignored, a reset replaces the in-flight fetch
 * and its outcome never lands, and load-more after completion fetches the
 * next page. Pure JVM: a gated [CatalogPages] fake on
 * `Dispatchers.Unconfined`; no coroutines-test.
 */
class CatalogSingleFlightTest {

    private class FakePages : CatalogPages {
        class Call(val reset: Boolean)

        val calls = CopyOnWriteArrayList<Call>()
        private val gates = CopyOnWriteArrayList<CompletableDeferred<CatalogPage>>()

        override suspend fun load(
            entity: SourceEntity,
            reset: Boolean,
            showLatest: Boolean,
            filterValues: FilterValues,
        ): CatalogPage {
            gates.add(CompletableDeferred())
            calls.add(Call(reset))
            return gates.last().await()
        }

        fun complete(index: Int, page: Int, vararg items: SourceNovelItem) {
            gates[index].complete(
                CatalogPage(
                    filters = emptyList(),
                    coverHeaders = emptyMap(),
                    items = items.toList(),
                    page = page,
                    endReached = items.isEmpty(),
                ),
            )
        }
    }

    private val entity = SourceEntity(
        id = 1,
        ecosystem = "lnreader",
        sourceId = "s1",
        identity = "lnreader_1",
        numericId = 1,
        displayName = "Catalog Source",
    )

    private fun newViewModel(pages: FakePages): CatalogViewModel = CatalogViewModel(
        identity = entity.identity,
        findEntity = { entity },
        pages = pages,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun paths(vm: CatalogViewModel) = vm.state.value.novels.map { it.path }

    @Test
    fun loadMoreDuringInFlightPageIsIgnored() = runBlocking<Unit> {
        val pages = FakePages()
        val vm = newViewModel(pages)
        assertEquals(1, pages.calls.size)
        pages.complete(index = 0, page = 1, SourceNovelItem("N1", "p1"))
        assertEquals(listOf("p1"), paths(vm))

        vm.loadMore()
        vm.loadMore()
        assertEquals("one page fetch at a time", 2, pages.calls.size)
        pages.complete(index = 1, page = 2, SourceNovelItem("N2", "p2"))
        assertEquals(listOf("p1", "p2"), paths(vm))
        assertEquals(2, vm.state.value.page)
        assertEquals(2, pages.calls.size)
    }

    @Test
    fun resetDuringFlightReplacesJobAndOldOutcomeNeverLands() = runBlocking<Unit> {
        val pages = FakePages()
        val vm = newViewModel(pages)
        pages.complete(index = 0, page = 1, SourceNovelItem("N1", "p1"))

        vm.loadMore()
        vm.applyFilters(emptyMap())
        assertEquals(3, pages.calls.size)
        assertTrue(pages.calls[2].reset)
        pages.complete(index = 1, page = 2, SourceNovelItem("N2", "p2"))
        assertEquals("the replaced fetch's late outcome never lands", listOf("p1"), paths(vm))
        pages.complete(index = 2, page = 1, SourceNovelItem("F1", "f1"))
        assertEquals(listOf("f1"), paths(vm))
        assertEquals(1, vm.state.value.page)
    }

    @Test
    fun loadMoreAfterCompletionFetchesNextPage() = runBlocking<Unit> {
        val pages = FakePages()
        val vm = newViewModel(pages)
        pages.complete(index = 0, page = 1, SourceNovelItem("N1", "p1"))
        vm.loadMore()
        pages.complete(index = 1, page = 2, SourceNovelItem("N2", "p2"))
        vm.loadMore()
        pages.complete(index = 2, page = 3, SourceNovelItem("N3", "p3"))
        assertEquals(listOf("p1", "p2", "p3"), paths(vm))
        assertEquals(3, vm.state.value.page)
        assertEquals(3, pages.calls.size)
    }
}
