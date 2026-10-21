package com.ckchoi.yesss24

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Vertical webtoon reader with a strictly bounded three-page bitmap window.
 *
 * At most three page bitmaps are retained, for [first visible page, +1, +2].
 * Advancing the scroll by one page drops the oldest bitmap and schedules
 * exactly one new page decode. Every other adapter row is a lightweight
 * placeholder. No full-book extraction or eager bitmap decoding is performed.
 */
internal class ComicVerticalReader(
 context:Context,
 private val pageCount:Int,
 initialPage:Int,
 private val decode:(Int,Int,Int)->Bitmap,
 private val onCurrentPage:(Int)->Unit,
 private val onCenterTap:()->Unit,
 private val onPageError:(Int,Exception)->Unit,
):RecyclerView(context) {
 private val readerContext=context
 private val density=resources.displayMetrics.density
 private val manager=LinearLayoutManager(context,VERTICAL,false).apply {
  isItemPrefetchEnabled=false
 }
 private val decoder=Executors.newSingleThreadExecutor{task->
  Thread(task,"ckreader-webtoon-page").apply{isDaemon=true}
 }
 private val epoch=AtomicInteger(0)
 private val pageHeights=IntArray(pageCount.coerceAtLeast(0))
 private val mainHandler=android.os.Handler(android.os.Looper.getMainLooper())
 private val holders=HashSet<PageRow>()
 private val rows=PageAdapter()
 private var firstVisible=initialPage.coerceIn(0,(pageCount-1).coerceAtLeast(0))
 private var programmaticScroll=false
 @Volatile private var disposed=false
 private val centerGesture=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
  override fun onDown(e:MotionEvent):Boolean=true
  override fun onSingleTapConfirmed(e:MotionEvent):Boolean {
   if(disposed || !ComicCenterTapPolicy.isCenter(e.x,e.y,width,height))return false
   onCenterTap()
   return true
  }
 })

 // Observe centre taps without intercepting vertical scroll, flings, or
 // page view touches. RecyclerView remains the sole owner of scroll events.
 override fun dispatchTouchEvent(event:MotionEvent):Boolean {
  if(!disposed)centerGesture.onTouchEvent(event)
  return super.dispatchTouchEvent(event)
 }

 init {
  layoutManager=manager
  adapter=rows
  itemAnimator=null
  // Recycled/hidden pages must not retain additional bitmaps.
  setItemViewCacheSize(0)
  isVerticalScrollBarEnabled=true
  overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS
  setBackgroundColor(Color.BLACK)
  addOnScrollListener(object:OnScrollListener(){
   override fun onScrolled(view:RecyclerView,dx:Int,dy:Int){followScroll()}
   override fun onScrollStateChanged(view:RecyclerView,newState:Int){followScroll()}
  })
  scrollToPage(firstVisible)
 }

 private fun allowed(position:Int):Boolean=
  ComicReadingPolicy.verticalWindow(firstVisible,pageCount).contains(position)

 private fun followScroll(){
  if(disposed||programmaticScroll)return
  val current=manager.findFirstVisibleItemPosition()
  if(current !in 0 until pageCount)return
  if(firstVisible!=current){
   firstVisible=current
   adjustWindow()
   onCurrentPage(current)
  }
 }

 private fun adjustWindow() {
  // Release outgoing page *before* decoding the newly entering page.
  for(holder in holders) {
   val position=holder.bindingAdapterPosition
   if(position!=NO_POSITION&&!allowed(position))holder.release()
  }
  for(holder in holders) {
   val position=holder.bindingAdapterPosition
   if(position!=NO_POSITION&&allowed(position))rows.load(holder,position)
  }
 }

 fun scrollToPage(position:Int){
  if(disposed||pageCount<=0)return
  firstVisible=position.coerceIn(0,pageCount-1)
  programmaticScroll=true
  manager.scrollToPositionWithOffset(firstVisible,0)
  adjustWindow()
  post {
   programmaticScroll=false
   if(!disposed)followScroll()
  }
 }

 fun moveViewport(direction:Int){
  if(disposed)return
  smoothScrollBy(0,(height.coerceAtLeast(1)*direction*.9f).toInt())
 }

 fun relayoutAt(position:Int){
  if(disposed)return
  epoch.incrementAndGet()
  pageHeights.fill(0)
  rows.notifyDataSetChanged()
  scrollToPage(position)
 }

 fun dispose(){
  if(disposed)return
  disposed=true
  epoch.incrementAndGet()
  decoder.shutdownNow()
  adapter=null
  holders.toList().forEach{it.release()}
  holders.clear()
 }

 private fun px(value:Float)=(value*density+0.5f).toInt()

 private inner class PageRow(
  val container:FrameLayout,
  val picture:ImageView,
  val notice:TextView,
 ):ViewHolder(container) {
  var token=0
  var loadingPage=-1
  var bitmap:Bitmap?=null
  fun release(){
   token++
   loadingPage=-1
   picture.setImageDrawable(null)
   bitmap?.recycle()
   bitmap=null
  }
 }

 private inner class PageAdapter:Adapter<PageRow>(){
  override fun getItemCount():Int=pageCount
  override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):PageRow{
   val container=FrameLayout(readerContext).apply{setBackgroundColor(Color.BLACK)}
   val picture=ImageView(readerContext).apply{
    scaleType=ImageView.ScaleType.FIT_XY
    setBackgroundColor(Color.BLACK)
   }
   val notice=TextView(readerContext).apply {
    text="이미지 불러오는 중…";textSize=12f
    setTextColor(Color.LTGRAY);gravity=Gravity.CENTER
   }
   container.addView(picture,FrameLayout.LayoutParams(-1,-1))
   container.addView(notice,FrameLayout.LayoutParams(-1,-1))
   return PageRow(container,picture,notice).also(holders::add)
  }

  override fun onBindViewHolder(holder:PageRow,position:Int){
   holders.add(holder)
   holder.release()
   val width=this@ComicVerticalReader.width.takeIf{it>0}
    ?:resources.displayMetrics.widthPixels.coerceAtLeast(1)
   val approximate=(width*1.45f).toInt().coerceAtLeast(px(350f))
   val rowHeight=pageHeights.getOrNull(position)?.takeIf{it>0}?:approximate
   holder.container.layoutParams=LayoutParams(LayoutParams.MATCH_PARENT,rowHeight)
   holder.notice.text="이미지 불러오는 중…"
   holder.notice.visibility=View.VISIBLE
   load(holder,position)
  }

  fun load(holder:PageRow,position:Int){
   if(disposed||!allowed(position)||holder.bitmap!=null||
    holder.loadingPage==position)return
   holder.loadingPage=position
   val token=holder.token
   val revision=epoch.get()
   val width=this@ComicVerticalReader.width.takeIf{it>0}
    ?:resources.displayMetrics.widthPixels.coerceAtLeast(1)
   try {
    decoder.execute {
     var bitmap:Bitmap?=null
     var failure:Exception?=null
     try {
      if(!disposed && revision==epoch.get()&&!Thread.currentThread().isInterrupted)
       bitmap=decode(position,(width*1.3f).toInt().coerceIn(400,3200),3600)
     }catch(e:Exception){failure=e}
     val result=bitmap
     val error=failure
     mainHandler.post {
      if(disposed||holder.token!=token||revision!=epoch.get()||
       holder.bindingAdapterPosition!=position||!allowed(position)){
       result?.recycle()
       return@post
      }
      holder.loadingPage=-1
      if(result==null){
       holder.notice.visibility=View.VISIBLE
       holder.notice.text="페이지 "+(position+1)+" 읽기 실패 · 눌러 다시 시도"
       if(error!=null)onPageError(position,error)
       holder.notice.setOnClickListener {
        if(!disposed)load(holder,position)
       }
      }else{
       val height=(width.toLong()*result.height/result.width.coerceAtLeast(1))
        .coerceIn(1L,24_000L).toInt()
       pageHeights[position]=height
       holder.bitmap=result
       holder.picture.setImageBitmap(result)
       holder.notice.visibility=View.GONE
       holder.container.layoutParams=holder.container.layoutParams.apply {
        this.height=height
       }
      }
     }
    }
   }catch(_:java.util.concurrent.RejectedExecutionException){/* activity closed */}
  }

  override fun onViewAttachedToWindow(holder:PageRow) {
   super.onViewAttachedToWindow(holder)
   val position=holder.bindingAdapterPosition
   if(position!=NO_POSITION)load(holder,position)
  }

  override fun onViewDetachedFromWindow(holder:PageRow){
   holder.release()
   super.onViewDetachedFromWindow(holder)
  }

  override fun onViewRecycled(holder:PageRow){
   holder.release()
   holders.remove(holder)
   super.onViewRecycled(holder)
  }
 }
}
