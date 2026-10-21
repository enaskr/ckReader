package com.ckchoi.elibrary.common

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

object ProviderChrome {
    private const val HEIGHT_DP = 46
    private data class Link(val title:String,val screen:String?=null)
    private val links=listOf(
        Link("교보"),
        Link("예스24"),
        Link("알라딘"),
        Link("리디북스"),
        Link("윌라"),
        Link("전체설정","settings")
    )

    fun install(activity: Activity, root: ViewGroup, current: String) {
        val dark=GlobalConfig.isDark(activity)
        root.tag = "elibrary-provider-root"
        val scroller=HorizontalScrollView(activity).apply {
            tag="elibrary-gnb"
            isHorizontalScrollBarEnabled=false
            overScrollMode=View.OVER_SCROLL_NEVER
            setBackgroundColor(if(dark) Color.rgb(24,35,52) else Color.WHITE)
        }
        val bar=LinearLayout(activity).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(activity,8),0,dp(activity,8),0)
        }
        links.forEach { link ->
            val active = link.title==current
            bar.addView(TextView(activity).apply {
                tag=if(active) "elibrary-gnb-active" else "elibrary-gnb-link"
                text=link.title
                textSize=14f
                gravity=Gravity.CENTER
                setPadding(dp(activity,13),0,dp(activity,13),0)
                setTextColor(if(active) (if(dark) Color.rgb(174,201,255) else Color.rgb(37,79,174)) else (if(dark) Color.rgb(216,226,241) else Color.rgb(60,75,98)))
                if(active)setTypeface(typeface,Typeface.BOLD)
                setOnClickListener {
                    if(!active) navigate(activity,current,link.title)
                }
            },LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(activity,HEIGHT_DP)))
        }
        scroller.addView(bar,ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(activity,HEIGHT_DP)))
        when(root) {
            is LinearLayout -> root.addView(scroller,0,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(activity,HEIGHT_DP)))
            is FrameLayout -> {
                for(i in 0 until root.childCount){
                    val child=root.getChildAt(i)
                    val lp=child.layoutParams as? FrameLayout.LayoutParams ?: continue
                    lp.topMargin += dp(activity,HEIGHT_DP)
                    child.layoutParams=lp
                }
                root.addView(scroller,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(activity,HEIGHT_DP),Gravity.TOP))
            }
            else -> root.addView(scroller,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(activity,HEIGHT_DP)))
        }
    }

    fun navigate(activity:Activity,current:String,target:String) {
        val link=links.firstOrNull{it.title==target}?:return
        val from=links.indexOfFirst{it.title==current}.coerceAtLeast(0)
        val to=links.indexOf(link)
        val intent=Intent(activity,AppNavigationRegistry.target(link.title)).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        link.screen?.let{intent.putExtra("screen",it)}
        activity.startActivity(intent)
        @Suppress("DEPRECATION")
        if(to>from) {
            activity.overridePendingTransition(R.anim.el_slide_in_right,R.anim.el_slide_out_left)
        } else {
            activity.overridePendingTransition(R.anim.el_slide_in_left,R.anim.el_slide_out_right)
        }
    }

    fun applySystemBars(activity: Activity) {
        val dark = GlobalConfig.isDark(activity)
        val status = if (dark) Color.rgb(17,28,45) else Color.WHITE
        val surface = if (dark) Color.rgb(15,25,40) else Color.rgb(247,249,252)
        @Suppress("DEPRECATION")
        run {
            activity.window.statusBarColor = status
            activity.window.navigationBarColor = surface
        }
        activity.window.decorView.setBackgroundColor(status)
        androidx.core.view.WindowCompat.getInsetsController(activity.window,activity.window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        recolor(activity.window.decorView, dark)
    }

    private fun recolor(view: View, dark: Boolean) {
        when (view.tag) {
            "elibrary-provider-root" -> view.setBackgroundColor(if(dark) Color.rgb(15,25,40) else Color.rgb(247,249,252))
            "elibrary-gnb" -> view.setBackgroundColor(if(dark) Color.rgb(24,35,52) else Color.WHITE)
            "elibrary-gnb-active" -> (view as? TextView)?.setTextColor(if(dark) Color.rgb(174,201,255) else Color.rgb(37,79,174))
            "elibrary-gnb-link" -> (view as? TextView)?.setTextColor(if(dark) Color.rgb(216,226,241) else Color.rgb(60,75,98))
        }
        if(view is ViewGroup) for(i in 0 until view.childCount) recolor(view.getChildAt(i),dark)
    }
    private fun dp(activity:Activity,value:Int)=(value*activity.resources.displayMetrics.density).toInt()
}
