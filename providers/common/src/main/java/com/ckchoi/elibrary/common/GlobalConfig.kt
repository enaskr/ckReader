package com.ckchoi.elibrary.common

import android.content.Context
import android.graphics.Color
import android.content.pm.PackageManager
import android.os.Environment
import android.webkit.WebView
import java.io.File

object GlobalConfig {
    private const val PREFS = "elibrary_native"
    private val defaults = mapOf(
        "kyobo" to "Kyobo",
        "yes24" to "Yes24",
        "aladin" to "Aladin",
        "ridi" to "Ridi",
        "willa" to "Willa"
    )

    /** Edition flag in the manifest: does not mutate the user's normal theme settings. */
    @Volatile private var einkEditionCache: Boolean? = null
    fun isEInk(context: Context): Boolean {
        einkEditionCache?.let { return it }
        val detected = runCatching {
            @Suppress("DEPRECATION")
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName, PackageManager.GET_META_DATA)
            appInfo.metaData?.getBoolean("com.ckchoi.ckreader.EINK_MODE",false) ?: false
        }.getOrDefault(false)
        einkEditionCache = detected
        return detected
    }

    /** Preserve the previous manual dark flag until the user selects a theme mode. */
    fun themeMode(context: Context): String {
        val preferences=context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored=preferences.getString("theme_mode",null)
        return if(isEInk(context))"light"
          else if(stored in listOf("light","dark","system"))stored!!
          else if(preferences.getBoolean("dark",true))"dark" else "light"
    }

    /** Evaluates device night mode only in 'system' mode; safe for JVM tests. */
    fun resolveDark(mode:String,systemNight:Boolean):Boolean=when(mode){
        "light"->false
        "dark"->true
        "system"->systemNight
        else->true
    }

    fun isDark(context: Context): Boolean {
        val systemNight=(context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return !isEInk(context) && resolveDark(themeMode(context),systemNight)
    }
    fun setThemeMode(context: Context,mode:String) {
        require(mode in setOf("light","dark","system")){"지원하지 않는 화면 테마"}
        val edit=context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("theme_mode",mode)
        if(mode!="system")edit.putBoolean("dark",mode=="dark")
        edit.apply()
    }
    fun setDark(context: Context, value: Boolean) = setThemeMode(context,if(value)"dark" else "light")

    fun folder(context: Context, service: String): String {
        val def = defaults[service] ?: service
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("download_folder_$service", def).orEmpty()
        return sanitizeFolder(raw).ifBlank { def }
    }

    fun setFolder(context: Context, service: String, value: String) {
        val clean = sanitizeFolder(value)
        require(clean.isNotBlank()) { "폴더명을 입력해주세요." }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("download_folder_$service", clean).apply()
    }

    fun appVersionText(context: Context): String {
        val version = try {
            if (android.os.Build.VERSION.SDK_INT >= 33)
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName
            else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }
        } catch (_: Exception) { null }
        return "e-Library " + (version ?: "") + " · " + context.packageName
    }

    fun relativePath(context: Context, service: String): String = "${Environment.DIRECTORY_DOWNLOADS}/ebook/${folder(context, service)}"
    fun downloadDir(context: Context, service: String): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ebook/${folder(context, service)}")

    fun applyWebTheme(context: Context, webView: WebView) {
        val dark = isDark(context)
        val theme = if (dark) "black" else "white"
        webView.setBackgroundColor(if (isEInk(context)) Color.WHITE else if (dark) Color.rgb(15,25,40) else Color.rgb(247,249,252))
        // WebView implementations can lag the framework API on vendor devices.
        // Theme tuning is cosmetic, so never let an unsupported provider crash the app.
        if (android.os.Build.VERSION.SDK_INT >= 29) runCatching { webView.isForceDarkAllowed = false }
        if (android.os.Build.VERSION.SDK_INT >= 33) runCatching { webView.settings.isAlgorithmicDarkeningAllowed = false }
        val einkCss = if(isEInk(context)) """
            html,body { background-color:#ffffff !important; color:#101010 !important; }
            * { animation:none !important; transition:none !important;
                text-shadow:none !important; box-shadow:none !important; }
        """.trimIndent() else ""
        val css = org.json.JSONObject.quote(SearchTheme.webCss+"\n"+einkCss)
        webView.evaluateJavascript(
            """(function(){
                document.documentElement.dataset.theme='$theme';
                document.documentElement.style.colorScheme='${if (dark) "dark" else "light"}';
                if(document.body){document.body.dataset.theme='$theme';}
                var style=document.getElementById('elibrary-search-theme');
                if(!style){style=document.createElement('style');style.id='elibrary-search-theme';(document.head||document.documentElement).appendChild(style);}
                style.textContent=$css;
            })();""".trimIndent(), null
        )
        (context as? android.app.Activity)?.let { ProviderChrome.applySystemBars(it) }
    }

    private fun sanitizeFolder(value: String): String = value.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
        .trim('.', ' ')
        .take(80)
}
