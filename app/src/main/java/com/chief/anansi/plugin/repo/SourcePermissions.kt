package com.chief.anansi.plugin.repo

/**
 * The capability declarations a source displays (
 * modeled on IReader's NETWORK/STORAGE/WEBVIEW declaration set). Resolution
 * lives in the plugin layer, from ecosystem plus engine reality; the UI
 * only renders what this resolves and never infers capabilities itself.
 *
 * For v0.1.0: JS ecosystems always declare NETWORK (plugins fetch through
 * the host bridge). WEBVIEW is declared only when the JS engine
 * outcome is the headless WebView fallback; the shipped outcome is the
 * bundled QuickJS engine, so it is never declared today. STORAGE is never
 * declared: the host exposes no file APIs to plugins. Declarations are
 * display-only in v0.1.0: declared and visible, never silently granted.
 */
enum class SourcePermission(val label: String) {
    NETWORK("Network"),
    STORAGE("Storage"),
    WEBVIEW("WebView"),
}

object SourcePermissions {

    fun resolve(ecosystem: String): List<SourcePermission> = when (ecosystem) {
        ECOSYSTEM_LNREADER -> listOf(SourcePermission.NETWORK)
        else -> emptyList()
    }
}
