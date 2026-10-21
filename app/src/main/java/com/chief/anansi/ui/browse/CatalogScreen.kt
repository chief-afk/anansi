package com.chief.anansi.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chief.anansi.R
import com.chief.anansi.plugin.SourceNovelItem
import com.chief.anansi.ui.components.EmptyState
import com.chief.anansi.ui.components.NovelCover
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * One source's catalog: Popular and Latest listing chips, the
 * source's own filter schema in a sheet where it offers one, a cover grid,
 * and explicit load-more pagination. Failures render the registry's error
 * with the designed copy style and retry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    identity: String,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenNovel: (SourceNovelItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CatalogViewModel = viewModel(
        key = identity,
        factory = CatalogViewModel.factory(identity),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showFilterSheet by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        CatalogHeader(
            title = state.sourceName,
            onBack = onBack,
            onOpenSearch = onOpenSearch,
        )
        ListingChips(
            showLatest = state.showLatest,
            filters = state.filters,
            onShowLatest = viewModel::showLatest,
            onOpenFilters = { showFilterSheet = true },
        )
        when {
            state.sourceMissing -> EmptyState(
                icon = painterResource(R.drawable.ic_globe),
                title = stringResource(R.string.details_source_missing_title),
                body = stringResource(R.string.details_source_missing_body),
            )

            state.loading -> SkeletonGrid()

            state.failed && state.novels.isEmpty() -> SourceErrorState(
                kind = state.failureKind,
                reason = state.failureReason,
                onRetry = viewModel::retry,
            )

            state.novels.isEmpty() -> EmptyState(
                icon = painterResource(R.drawable.ic_globe),
                title = stringResource(R.string.catalog_empty_title),
                body = stringResource(R.string.catalog_empty_body),
            )

            else -> NovelGrid(
                novels = state.novels,
                coverHeaders = state.coverHeaders,
                onOpenNovel = onOpenNovel,
                endReached = state.endReached,
                loadingMore = state.loadingMore,
                onLoadMore = viewModel::loadMore,
                failed = state.failed,
                failureKind = state.failureKind,
                failureReason = state.failureReason,
                onRetry = viewModel::retry,
            )
        }
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            FilterSheetContent(
                filters = state.filters,
                applied = state.appliedFilterValues,
                onApply = { values ->
                    showFilterSheet = false
                    viewModel.applyFilters(values)
                },
                onReset = {
                    showFilterSheet = false
                    viewModel.applyFilters(emptyMap())
                },
            )
        }
    }
}

@Composable
private fun CatalogHeader(
    title: String,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 4.dp, end = 4.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.cd_back),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenSearch) {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = stringResource(R.string.cd_search),
            )
        }
    }
}

@Composable
private fun ListingChips(
    showLatest: Boolean,
    filters: List<CatalogFilter>,
    onShowLatest: (Boolean) -> Unit,
    onOpenFilters: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        FilterChip(
            selected = !showLatest,
            onClick = { onShowLatest(false) },
            label = { Text(stringResource(R.string.catalog_chip_popular)) },
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = showLatest,
            onClick = { onShowLatest(true) },
            label = { Text(stringResource(R.string.catalog_chip_latest)) },
        )
        Spacer(Modifier.weight(1f))
        if (filters.isNotEmpty()) {
            IconButton(onClick = onOpenFilters) {
                Icon(
                    painter = painterResource(R.drawable.ic_filter),
                    contentDescription = stringResource(R.string.cd_catalog_filter),
                )
            }
        }
    }
}

@Composable
private fun NovelGrid(
    novels: List<SourceNovelItem>,
    coverHeaders: Map<String, String>,
    onOpenNovel: (SourceNovelItem) -> Unit,
    endReached: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    failed: Boolean,
    failureKind: String,
    failureReason: String,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(novels, key = { it.path }) { novel ->
                CatalogCell(
                    novel = novel,
                    coverHeaders = coverHeaders,
                    onOpenNovel = onOpenNovel,
                )
            }
        }
        when {
            failed -> InlineError(
                kind = failureKind,
                reason = failureReason,
                onRetry = onRetry,
            )

            !endReached -> LoadMoreRow(loading = loadingMore, onLoadMore = onLoadMore)

            else -> Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CatalogCell(
    novel: SourceNovelItem,
    coverHeaders: Map<String, String>,
    onOpenNovel: (SourceNovelItem) -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onOpenNovel(novel) },
    ) {
        NovelCover(
            title = novel.name,
            coverUrl = novel.cover,
            headers = coverHeaders,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        )
        Text(
            text = novel.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** The board's loading skeleton: placeholder cells in the catalog grid shape. */
@Composable
private fun SkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items((0 until 9).toList(), key = { it }) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            ) {}
        }
    }
}

@Composable
private fun LoadMoreRow(loading: Boolean, onLoadMore: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
            FilterChip(
                selected = false,
                onClick = onLoadMore,
                label = { Text(stringResource(R.string.catalog_load_more)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun InlineError(kind: String, reason: String, onRetry: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = kind,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

/** The source's own filter schema, rendered passthrough over a values draft. */
@Composable
private fun FilterSheetContent(
    filters: List<CatalogFilter>,
    applied: FilterValues,
    onApply: (FilterValues) -> Unit,
    onReset: () -> Unit,
) {
    var draft by remember(filters, applied) { mutableStateOf(applied) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.filter_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            filters.forEach { filter ->
                FilterField(
                    filter = filter,
                    value = draft[filter.key],
                    onChange = { value ->
                        draft = if (value == null) {
                            draft - filter.key
                        } else {
                            draft + (filter.key to value)
                        }
                    },
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.filter_reset))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onApply(draft) }) {
                Text(stringResource(R.string.filter_apply))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterField(
    filter: CatalogFilter,
    value: JsonElement?,
    onChange: (JsonElement?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        when (filter) {
            is CatalogFilter.Text -> {
                Text(
                    text = filter.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = (value as? JsonPrimitive)?.content ?: filter.value,
                    onValueChange = { onChange(JsonPrimitive(it)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }

            is CatalogFilter.Picker -> FilterOptionsRow(
                label = filter.label,
                options = filter.options,
                selectedValue = (value as? JsonPrimitive)?.content ?: filter.selected,
                onToggle = { option, currentlySelected ->
                    onChange(
                        if (currentlySelected) null else JsonPrimitive(option.value),
                    )
                },
            )

            is CatalogFilter.Toggle -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = filter.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = (value as? JsonPrimitive)?.booleanOrNull ?: filter.checked,
                    onCheckedChange = { onChange(JsonPrimitive(it)) },
                )
            }

            is CatalogFilter.Checks -> {
                val selected = value as? JsonArray
                FilterOptionsRow(
                    label = filter.label,
                    options = filter.options,
                    selectedValues = selected?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet()
                        ?: filter.selected,
                    onToggle = { option, currentlySelected ->
                        val current = selected?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet()
                            ?: filter.selected
                        val next = if (currentlySelected) current - option.value else current + option.value
                        onChange(JsonArray(next.map(::JsonPrimitive)))
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterOptionsRow(
    label: String,
    options: List<FilterOption>,
    selectedValue: String? = null,
    selectedValues: Set<String>? = null,
    onToggle: (FilterOption, Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            options.forEach { option ->
                val selected = selectedValues?.contains(option.value) ?: (selectedValue == option.value)
                FilterChip(
                    selected = selected,
                    onClick = { onToggle(option, selected) },
                    label = { Text(option.label) },
                )
            }
        }
    }
}
