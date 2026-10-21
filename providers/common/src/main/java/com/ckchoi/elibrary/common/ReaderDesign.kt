package com.ckchoi.elibrary.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.LinearLayout

/**
 * Native ckReader design language, adapted from the public HeyClicky light
 * visual aesthetic and Cobalt dark blue theme (without third-party artwork).
 *
 * Semantics are shared between the library, reader, dialogs and system chrome.
 * Individual reader page-color overrides remain independent of these tokens.
 */
data class ReaderColors(
 val name:String,
 val dark:Boolean,
 val background:String,
 val surface:String,
 val raised:String,
 val text:String,
 val muted:String,
 val border:String,
 val primary:String,
 val onPrimary:String,
 val subtle:String,
 val onSubtle:String,
 val error:String,
 val heroStart:String,
 val heroEnd:String,
 val dot:String
) {
 fun bg()=Color.parseColor(background)
 fun card()=Color.parseColor(surface)
 fun raisedColor()=Color.parseColor(raised)
 fun fg()=Color.parseColor(text)
 fun secondary()=Color.parseColor(muted)
 fun outline()=Color.parseColor(border)
 fun brand()=Color.parseColor(primary)
 fun brandText()=Color.parseColor(onPrimary)
 fun tint()=Color.parseColor(subtle)
 fun tintText()=Color.parseColor(onSubtle)
 fun errorColor()=Color.parseColor(error)
}

object ReaderDesign {
 val heyclicky=ReaderColors(
  name="Heyclicky",dark=false,background="#F7F9FF",surface="#FFFFFF",
  raised="#EDF4FF",text="#15213A",muted="#52647E",border="#BACFEB",
  primary="#245CFF",onPrimary="#FFFFFF",subtle="#E6EEFF",
  onSubtle="#2246AE",error="#B42337",heroStart="#EAF3FF",
  heroEnd="#F4F8FF",dot="#CCDFFA")
 val eink=ReaderColors(
  name="EInk",dark=false,background="#FFFFFF",surface="#FFFFFF",
  raised="#F5F5F5",text="#101010",muted="#484848",border="#777777",
  primary="#161616",onPrimary="#FFFFFF",subtle="#EEEEEE",
  onSubtle="#121212",error="#222222",heroStart="#FFFFFF",
  heroEnd="#FFFFFF",dot="#FFFFFF")
 val cobalt=ReaderColors(
  name="Cobalt",dark=true,background="#081729",surface="#102540",
  raised="#1B3658",text="#F3F7FF",muted="#B7CBE6",border="#345171",
  primary="#70ADFF",onPrimary="#071B31",subtle="#1E3B60",
  onSubtle="#D6E8FF",error="#FF9DA8",heroStart="#163B67",
  heroEnd="#0B2545",dot="#315C86")

 fun tokens(context:Context):ReaderColors=if(GlobalConfig.isEInk(context))eink else if(GlobalConfig.isDark(context))cobalt else heyclicky
 fun dp(context:Context,value:Float):Float=value*context.resources.displayMetrics.density
 /** Platform elevation/tints must not paint a rectangle outside a custom rounded button. */
 fun flatButton(view:View) {
  view.backgroundTintList=null
  view.stateListAnimator=null
  view.elevation=0f;view.translationZ=0f
  view.clipToOutline=true
 }
 private fun isButton(view:View)=(view is Button&&view !is CompoundButton)||view is ImageButton
 private fun buttonRow(view:View):Boolean = isButton(view)||
  view is LinearLayout&&view.orientation==LinearLayout.HORIZONTAL&&
   (0 until view.childCount).any{isButton(view.getChildAt(it))&&view.getChildAt(it).visibility!=View.GONE}
 /** Run after explicit LayoutParams have been assigned; repeated calls keep the same gap. */
 fun finishButtons(root:View) {
  if(isButton(root))flatButton(root)
  if(root !is ViewGroup)return
  for(index in 0 until root.childCount)finishButtons(root.getChildAt(index))
  if(root !is LinearLayout||root.orientation!=LinearLayout.VERTICAL)return
  val gap=dp(root.context,8f).toInt()
  var previous:View?=null
  for(index in 0 until root.childCount) {
   val child=root.getChildAt(index)
   if(child.visibility==View.GONE)continue
   val before=previous
   if(before!=null&&buttonRow(before)&&buttonRow(child)) {
    val params=child.layoutParams as? ViewGroup.MarginLayoutParams
    val bottom=(before.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin?:0
    if(params!=null&&params.topMargin+bottom<gap) {
     params.topMargin=gap-bottom;child.layoutParams=params
    }
   }
   previous=child
  }
 }
 fun shape(context:Context,tokens:ReaderColors,fill:Int=tokens.card(),
  border:Int=tokens.outline(),radius:Float=15f):GradientDrawable =
  GradientDrawable().apply {
   setColor(fill)
   cornerRadius=dp(context,if(tokens.dark)minOf(radius,11f) else radius)
   setStroke(dp(context,1f).coerceAtLeast(1f).toInt(),border)
  }

 /** Small decorative dotted-grid hero, clipped to rounded native surfaces. */
 fun hero(context:Context,tokens:ReaderColors):Drawable =
  if(tokens.name=="EInk") GradientDrawable().apply {
   setColor(Color.WHITE)
   cornerRadius=dp(context,6f)
   setStroke(dp(context,1f).toInt().coerceAtLeast(1),tokens.outline())
  }
  else if(tokens.dark)GradientDrawable(GradientDrawable.Orientation.TL_BR,
   intArrayOf(Color.parseColor(tokens.heroStart),Color.parseColor(tokens.heroEnd))).apply {
    cornerRadius=dp(context,12f)
    setStroke(dp(context,1f).toInt().coerceAtLeast(1),tokens.outline())
   }
  else Dots(context,tokens)

 private class Dots(private val context:Context,private val c:ReaderColors):Drawable() {
  private val base=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor(c.heroStart)}
  private val dots=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor(c.dot)}
  private val border=Paint(Paint.ANTI_ALIAS_FLAG).apply{
   color=c.outline();style=Paint.Style.STROKE;strokeWidth=dp(context,1f)
  }
  override fun draw(canvas:Canvas) {
   val b=bounds
   val rect=RectF(b.left.toFloat(),b.top.toFloat(),b.right.toFloat(),b.bottom.toFloat())
   val radius=dp(context,20f)
   canvas.drawRoundRect(rect,radius,radius,base)
   val checkpoint=canvas.save()
   val clip=Path().apply{addRoundRect(rect,radius,radius,Path.Direction.CW)}
   canvas.clipPath(clip)
   val step=dp(context,18f)
   var y=b.top+step/2
   while(y<b.bottom) {
    var x=b.left+step/2
    while(x<b.right) {
     canvas.drawCircle(x,y,dp(context,0.75f),dots)
     x+=step
    }
    y+=step
   }
   canvas.restoreToCount(checkpoint)
   canvas.drawRoundRect(rect,radius,radius,border)
  }
  override fun setAlpha(alpha:Int){base.alpha=alpha;dots.alpha=alpha}
  override fun setColorFilter(colorFilter:ColorFilter?){base.colorFilter=colorFilter}
  @Deprecated("Deprecated in Java")
  override fun getOpacity():Int=PixelFormat.TRANSLUCENT
 }

 /** WCAG contrast helper for deterministic JVM token regression tests. */
 fun contrast(foreground:String,background:String):Double {
  fun luminance(hex:String):Double {
   val chars=hex.removePrefix("#")
   require(chars.length==6)
   val values=(0..2).map {i->
    val channel=chars.substring(i*2,i*2+2).toInt(16)/255.0
    if(channel<=0.04045)channel/12.92 else Math.pow((channel+0.055)/1.055,2.4)
   }
   return .2126*values[0]+.7152*values[1]+.0722*values[2]
  }
  val a=luminance(foreground);val b=luminance(background)
  return (maxOf(a,b)+.05)/(minOf(a,b)+.05)
 }
}
