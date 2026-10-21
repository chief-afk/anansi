package com.chief.anansi.ui.reader

import android.graphics.Typeface as AndroidTypeface
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color

/**
 * Reader-surface color pairs: the reading surface is independent
 * of the app-wide theme mode, whose seed system keeps owning app chrome.
 * Pure-black OLED is the named backlog chip and is deliberately absent here.
 */
enum class ReaderTheme(
    val background: Color,
    val onBackground: Color,
    val muted: Color,
) {
    DARK(
        background = Color(0xFF1B1A21),
        onBackground = Color(0xFFE5E1E9),
        muted = Color(0xFFABA5B4),
    ),
    SLATE(
        background = Color(0xFF23272F),
        onBackground = Color(0xFFD9DEE6),
        muted = Color(0xFF9AA3AE),
    ),
    PAPER(
        background = Color(0xFFF5EFE3),
        onBackground = Color(0xFF40382E),
        muted = Color(0xFF8A7F70),
    ),
}

/** The typeface chips; Rounded falls back to the default family. */
enum class ReaderTypeface {
    SYSTEM, SERIF, ROUNDED;

    val fontFamily: FontFamily
        get() = when (this) {
            SYSTEM -> FontFamily.Default
            SERIF -> FontFamily.Serif
            // The platform rounded family ships on newer Android versions;
            // Typeface.create degrades to the default family elsewhere.
            ROUNDED -> roundedFamily
        }
}

private val roundedFamily by lazy {
    FontFamily(AndroidTypeface.create("sans-serif-rounded", AndroidTypeface.NORMAL))
}

/** One reader-settings snapshot; global scope, live-applied. */
data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.DARK,
    val textSizeSp: Int = 17,
    val lineSpacing: Float = 1.7f,
    val typeface: ReaderTypeface = ReaderTypeface.SYSTEM,
    val keepScreenOn: Boolean = false,
    val volumeKeysScroll: Boolean = false,
)
