package com.chief.anansi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import kotlin.math.abs

/**
 * Novel cover art with the designed fallback: a
 * seed-colored container carrying the title initials while the cover is
 * missing or still loading. All cover requests go through Coil's shared
 * image loader.
 */
@Composable
fun NovelCover(
    title: String,
    coverUrl: String?,
    modifier: Modifier = Modifier,
) {
    if (coverUrl == null) {
        CoverPlaceholder(title = title, modifier = modifier)
    } else {
        SubcomposeAsyncImage(
            model = coverUrl,
            contentDescription = null,
            modifier = modifier,
            loading = {
                CoverPlaceholder(title = title, modifier = Modifier.fillMaxSize())
            },
            error = {
                CoverPlaceholder(title = title, modifier = Modifier.fillMaxSize())
            },
        )
    }
}

@Composable
private fun CoverPlaceholder(title: String, modifier: Modifier = Modifier) {
    val containers = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
    )
    val onContainers = listOf(
        MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer,
        MaterialTheme.colorScheme.onTertiaryContainer,
    )
    val pick = abs(title.hashCode()) % containers.size
    Box(
        modifier = modifier.background(containers[pick]),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = titleInitials(title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = onContainers[pick],
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(8.dp),
        )
    }
}

private fun titleInitials(title: String): String {
    // Significant-word initials (Beware of Chicken -> BC).
    val significant = title
        .trim()
        .split(Regex("\\s+"))
        .filterNot { it.isEmpty() || (it.length > 1 && it.lowercase() in STOP_WORDS) }
    return when {
        significant.isEmpty() -> "?"
        significant.size == 1 -> significant[0].take(2)
        else -> "${significant[0].first()}${significant[1].first()}"
    }.uppercase()
}

private val STOP_WORDS = setOf("of", "the", "a", "an", "and", "or", "to", "in")
