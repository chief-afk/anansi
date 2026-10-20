package com.chief.anansi.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
fun LibraryScreen(onBrowseSources: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.tab_library))
        EmptyState(
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
    }
}
