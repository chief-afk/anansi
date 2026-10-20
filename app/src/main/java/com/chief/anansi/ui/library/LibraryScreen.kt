package com.chief.anansi.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.chief.anansi.data.dao.LibraryNovel
import com.chief.anansi.ui.components.EmptyState
import com.chief.anansi.ui.components.NovelCover
import com.chief.anansi.ui.components.ScreenTitle

@Composable
fun LibraryScreen(
    onBrowseSources: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val library by viewModel.library.collectAsStateWithLifecycle(initialValue = null)
    Column(modifier = modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.tab_library))
        val current = library
        when {
            // Before the database has spoken its first word.
            current == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            current.isEmpty() -> EmptyState(
                icon = painterResource(R.drawable.ic_spider),
                title = stringResource(R.string.library_empty_title),
                body = stringResource(R.string.library_empty_body),
            ) {
                Button(onClick = onBrowseSources) {
                    Icon(
                        painter = painterResource(R.drawable.ic_tab_browse),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.library_empty_action))
                }
            }

            else -> LibraryGrid(current)
        }
    }
}

@Composable
private fun LibraryGrid(library: List<LibraryNovel>) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(library, key = { it.novel.id }) { item ->
            LibraryCell(item)
        }
    }
}

@Composable
private fun LibraryCell(item: LibraryNovel) {
    Column {
        Box {
            NovelCover(
                title = item.novel.title,
                coverUrl = item.novel.coverUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp)),
            )
            if (item.unreadCount > 0) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                ) {
                    Text(
                        text = item.unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(
            text = item.novel.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = continueAtCaption(item),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Ch. N" from the progress record, or the All-read state at zero unread. */
@Composable
private fun continueAtCaption(item: LibraryNovel): String =
    if (item.unreadCount == 0) {
        stringResource(R.string.library_all_read)
    } else {
        stringResource(
            R.string.library_continue_chapter,
            item.continueChapterOrder ?: 1,
        )
    }
