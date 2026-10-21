package com.ckchoi.elibrary.common

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.ByteArrayInputStream

/**
 * Minimal HTTPS-only browser kept inside the e-Library APK.
 * Web navigation stays inside this APK and never delegates to an external viewer application.
 */
class InAppBrowserActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val start = intent.getStringExtra("url").orEmpty()
        val uri = Uri.parse(start)
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
            finish()
            return
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = intent.getStringExtra("title").orEmpty().ifBlank { uri.host.orEmpty() }
            setPadding(24, 18, 24, 18)
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val next = request.url
                    if (next.scheme == "https" && !next.host.isNullOrBlank() && next.userInfo == null) return false
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.scheme != "https") {
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                    }
                    return null
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel()
                }
            }
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress >= 100) android.view.View.GONE else android.view.View.VISIBLE
                }
            }
        }
        root.addView(title)
        root.addView(progress, LinearLayout.LayoutParams(-1, 4))
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ProviderChrome.applySystemBars(this)
        if (state == null) web.loadUrl(start)
    }

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
