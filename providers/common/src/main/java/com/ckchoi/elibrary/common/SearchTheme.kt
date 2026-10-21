package com.ckchoi.elibrary.common

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.widget.EditText

/** Search controls follow the app preference, not the system's WebView/widget theme. */
object SearchTheme {
    fun apply(input: EditText, dark: Boolean) {
        val text = Color.parseColor(if (dark) "#E8EFF9" else "#1D2830")
        val hint = Color.parseColor(if (dark) "#AAB9CF" else "#52606D")
        val accent = Color.parseColor(if (dark) "#9BBFFF" else "#245DA8")
        input.setTextColor(text)
        input.setHintTextColor(hint)
        input.setLinkTextColor(accent)
        input.highlightColor = Color.parseColor(if (dark) "#46628C" else "#C4DCF8")
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { input.isForceDarkAllowed = false }
            runCatching {
                input.textCursorDrawable = GradientDrawable().apply {
                    setColor(accent)
                    setSize((2 * input.resources.displayMetrics.density).toInt().coerceAtLeast(1), input.lineHeight)
                }
            }
            runCatching {
                listOf(input.textSelectHandle, input.textSelectHandleLeft, input.textSelectHandleRight)
                    .forEach { it?.mutate()?.setTint(accent) }
            }
        }
    }

    // Explicit foreground/background pairs also cover search fields created after page load.
    val webCss = """
        :root[data-theme="white"] { --el-search-bg:#FFFFFF; --el-search-fg:#1D2830; --el-search-hint:#52606D; --el-search-line:#CDD7CD; --el-search-focus:#245DA8; --el-search-selection:#C4DCF8; }
        :root[data-theme="black"] { --el-search-bg:#1B2A3E; --el-search-fg:#E8EFF9; --el-search-hint:#AAB9CF; --el-search-line:#3A4D68; --el-search-focus:#9BBFFF; --el-search-selection:#46628C; }
        :root[data-theme] input[type="search"], :root[data-theme] .unifiedSearch input:not([type]), :root[data-theme] .unifiedSearch input[type="text"] {
            color:var(--el-search-fg)!important;
            -webkit-text-fill-color:var(--el-search-fg)!important;
            background-color:var(--el-search-bg)!important;
            border-color:var(--el-search-line)!important;
            caret-color:var(--el-search-focus)!important;
            opacity:1;
        }
        :root[data-theme] #search, :root[data-theme] .catalogSearch.unifiedSearch .searchRow {
            background-color:var(--el-search-bg)!important;
            border-color:var(--el-search-line)!important;
        }
        :root[data-theme] input[type="search"]::placeholder, :root[data-theme] .unifiedSearch input::placeholder {
            color:var(--el-search-hint)!important;
            -webkit-text-fill-color:var(--el-search-hint)!important;
            opacity:1!important;
        }
        :root[data-theme] input[type="search"]:focus, :root[data-theme] .unifiedSearch input:focus {
            outline:2px solid var(--el-search-focus)!important; outline-offset:1px;
        }
        :root[data-theme] input[type="search"]::selection, :root[data-theme] .unifiedSearch input::selection {
            color:var(--el-search-fg); background:var(--el-search-selection);
        }
        :root[data-theme] input[type="search"]:-webkit-autofill, :root[data-theme] .unifiedSearch input:-webkit-autofill {
            -webkit-text-fill-color:var(--el-search-fg)!important;
            -webkit-box-shadow:0 0 0 1000px var(--el-search-bg) inset!important;
        }
    """.trimIndent()
}
