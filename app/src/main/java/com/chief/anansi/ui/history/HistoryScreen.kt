package com.chief.anansi.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.chief.anansi.R
import com.chief.anansi.ui.components.EmptyState
import com.chief.anansi.ui.components.ScreenTitle

@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.tab_history))
        EmptyState(
            icon = painterResource(R.drawable.ic_tab_history),
            title = stringResource(R.string.history_empty_title),
        )
    }
}
