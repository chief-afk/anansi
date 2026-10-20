package com.chief.anansi.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.chief.anansi.R

enum class AnansiTab(
    val route: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
    @DrawableRes val selectedIconRes: Int,
) {
    LIBRARY("library", R.string.tab_library, R.drawable.ic_tab_library, R.drawable.ic_tab_library_filled),
    UPDATES("updates", R.string.tab_updates, R.drawable.ic_tab_updates, R.drawable.ic_tab_updates_filled),
    HISTORY("history", R.string.tab_history, R.drawable.ic_tab_history, R.drawable.ic_tab_history_filled),
    BROWSE("browse", R.string.tab_browse, R.drawable.ic_tab_browse, R.drawable.ic_tab_browse_filled),
    MORE("more", R.string.tab_more, R.drawable.ic_tab_more, R.drawable.ic_tab_more_filled),
}
