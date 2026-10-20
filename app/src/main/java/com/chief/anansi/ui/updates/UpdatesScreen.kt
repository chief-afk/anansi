package com.chief.anansi.ui.updates

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chief.anansi.R
import com.chief.anansi.ui.components.EmptyState
import com.chief.anansi.ui.components.ScreenTitle

@Composable
fun UpdatesScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.tab_updates))
        Text(
            text = stringResource(R.string.updates_manual_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp),
        )
        EmptyState(
            icon = painterResource(R.drawable.ic_check),
            title = stringResource(R.string.updates_empty_title),
        )
    }
}
