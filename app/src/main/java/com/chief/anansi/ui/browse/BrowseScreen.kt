package com.chief.anansi.ui.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.chief.anansi.R
import com.chief.anansi.ui.components.EmptyState
import com.chief.anansi.ui.components.ScreenTitle

// The Add repository button arrives with
// the source management flow, and a button without a flow would pretend to work.
@Composable
fun BrowseScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.tab_browse))
        EmptyState(
            icon = painterResource(R.drawable.ic_globe),
            title = stringResource(R.string.browse_empty_title),
            body = stringResource(R.string.browse_empty_body),
        )
    }
}
