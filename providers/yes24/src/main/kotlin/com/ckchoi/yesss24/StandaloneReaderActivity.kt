package com.ckchoi.yesss24

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.app.AlertDialog
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import com.ckchoi.elibrary.common.ReaderDesign
import com.ckchoi.elibrary.common.GlobalConfig
import com.ckchoi.elibrary.common.ReaderFonts
import com.ckchoi.elibrary.common.ReaderBackgrounds
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Html
import android.text.InputType
import android.util.Xml
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import com.ckchoi.elibrary.common.ArchiveRangeSource
import com.ckchoi.elibrary.common.RangeReadChannel
import java.io.FileInputStream
import org.apache.commons.compress.archivers.zip.ZipFile as SeekZip
import java.util.Locale
import kotlin.math.min

/** Native reader for local PDF and EPUB, including books prepared by the YES24 client. */
class StandaloneReaderActivity : Activity() {
 private val worker = Executors.newSingleThreadExecutor()
 private val pagePrefetch=Executors.newFixedThreadPool(2)
 private var src:Uri?=null
 private var docId=""
 private var format=""
 private var page=0
 private var total=0
 private var zoom=110
 private var lineHeight=160
 private var hMargin=12
 private var vMargin=8
 private var paragraph=8
 private var indent=0
 private var fontChoice=0
 private var fontBold=false
 private var appliedFontRevision=-1L
 private var alignmentChoice=0
 private var backgroundChoice=0
 private var brightness=100
 private var systemBrightness=true
 private var showPageInfo=true
 private var touchPaging=true
 private var volumePaging=false
 private var rotationLock=false
 private var keepScreenOn=true
 private var autoScroll=false
 private var pageTurnMs=600
 private var pageTurnOverlay:PaperPageTurnView?=null
 private var pageTurnAnimator:ValueAnimator?=null
 private var gestureBrightness=false
 private var dualPageWide=true
 // Comic settings are independent of the PDF/EPUB spread preference.
 private var comicDirection=ComicReadingPolicy.Direction.HORIZONTAL
 private var comicTwoPage=false
 private var comicFullscreen=false
 private var comicFullscreenPopup:PopupWindow?=null
 private var comicVerticalList:ComicVerticalReader?=null
 private var lastComicSpreadMode=false
 private var comicStreamFallbackAttempted=false
 private var lastSpreadMode=false
 private var spreadIndex=0
 private var pendingSpreadIndex:Int?=null
 private var pendingLastSpread=false
 private var tts:TextToSpeech?=null
 private var ttsReady=false
 private var ttsPlaying=false
 private var ttsPaused=false
 private var ttsReceiverRegistered=false
 private val ttsNotificationReceiver=object:BroadcastReceiver() {
  override fun onReceive(context:Context?,intent:Intent?) {
   when(intent?.getStringExtra(ReaderTtsNotificationService.EXTRA_COMMAND)){
    ReaderTtsNotificationService.COMMAND_PAUSE->if(ttsPlaying)pauseSpeaking()
    ReaderTtsNotificationService.COMMAND_RESUME->if(!ttsPlaying)resumeSpeaking()
    ReaderTtsNotificationService.COMMAND_STOP->stopSpeaking()
   }
  }
 }
 private fun notifyTts() {
  if(ttsPlaying||ttsPaused){
   val subtitle=speechParts.getOrNull(speechPart)?.take(90).orEmpty()
   val location="${(speechPreparedPage+1).coerceAtLeast(1)} / $total · $subtitle"
   runCatching{ReaderTtsNotificationService.update(this,ttsPlaying,location)}
    .onFailure{android.util.Log.w("CremaReader","TTS notification",it)}
  }else runCatching{ReaderTtsNotificationService.close(this)}
 }

 private var speechWakeLock:android.os.PowerManager.WakeLock?=null
 private fun updateSpeechWakeLock(){
  if(ttsPlaying){
   if(speechWakeLock?.isHeld!=true)runCatching {
    speechWakeLock=(getSystemService(POWER_SERVICE) as android.os.PowerManager)
     .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"ckReader:tts-playback").apply {
      setReferenceCounted(false)
      acquire(3*60*60*1000L)
     }
   }
  } else runCatching {speechWakeLock?.takeIf{it.isHeld}?.release()}
 }

 private var ttsRate=1.0f
 private var ttsPitch=1.0f
 private var ttsVoiceIndex=0
 private var ttsEngine=""
 private var ttsVoiceName=""
 private var ttsMode="ai"
 private var aiVoiceIndex=0
 private var ridiVoiceIndex=0
 private var willaVoiceIndex=0
 private var aiVoice:AiVoiceEngine?=null
 private var listeningAction:Button?=null
 private var ttsAutoLanguage=false
 private var ttsReadParentheses=true
 private var ttsSkipHanja=false
 private var speechParts=emptyList<String>()
 private var speechChunks=emptyList<SpeechText.Part>()
 private var speechEnglish=emptyList<Boolean>()
 private var speechPart=0
 private var requestedSpeechOffset:Int?=null
 private var requestedPdfSpeech:String?=null
 private var speechSequence=0
 private var speechPreparedPage=-1
 private var speechPreparedStep=1
 private var speechFollowing=true
 private var speechStartEpoch=0
 private data class SpeechStart(val text:String,val offset:Int)
 private var requestedEpubSpeech:SpeechStart?=null
 private var pendingEpubSpeechStart:(()->Unit)?=null
 private data class PdfSpeechRegion(val page:Int,val bounds:RectF)
 private var pdfSpeechRegions=emptyList<PdfSpeechRegion>()
 private var speechHighlightEpoch=0
 private var timerDeadline=0L
 private var lastChapterNavigation=0L
 private val mainHandler=Handler(Looper.getMainLooper())
 private val scrollTick=object:Runnable{override fun run(){
  if(finished||!autoScroll||format!="epub")return
  if(ttsPlaying){mainHandler.postDelayed(this,500);return}
  val view=web
  if(view!=null&&view.progress==100&&System.currentTimeMillis()-lastChapterNavigation>8000L){
   navigate(1);lastChapterNavigation=System.currentTimeMillis()
  }
  mainHandler.postDelayed(this,500)
 }}
 private var toc=emptyList<Pair<String,Int>>()
 @Volatile private var generation=0
 @Volatile private var finished=false
 private var pdfFd:ParcelFileDescriptor?=null
 private var pdf:PdfRenderer?=null
 private var pdfSpeech:PdfSpeechExtractor?=null
 private var zip:ZipFile?=null
 private var comicSeekZip:SeekZip?=null
 private var comicSeekFd:ParcelFileDescriptor?=null
 @Volatile private var comicRangeChannel:RangeReadChannel?=null
 private var comicPages=emptyList<String>()
 private var txtPages=emptyList<String>()
 private var comicLoadingView:TextView?=null
 private var comicLoadingStage=""
 private var comicLoadingBytes=-1L
 private var comicLoadingTotal=-1L
 private var comicLoadingStarted=0L
 private val comicLoadingTicker=object:Runnable {
  override fun run() {
   if(finished || comicLoadingStage.isEmpty())return
   updateComicLoadingText()
   mainHandler.postDelayed(this,1000)
  }
 }
 private fun updateComicLoadingText(){
  val label=comicLoadingView?.takeIf{it.parent==body}?:return
  val seconds=((android.os.SystemClock.elapsedRealtime()-comicLoadingStarted)/1000).coerceAtLeast(0L)
  val bytes=comicLoadingBytes
  val total=comicLoadingTotal
  val size=if(bytes<0)""
   else if(total>0){
    val percent=(bytes.coerceIn(0,total)*100/total).toInt()
    "\n다운로드 "+percent+"% · "+(bytes/1048576)+" / "+(total/1048576)+" MB"
   }else "\n전송된 데이터 "+(bytes/1048576)+" MB"
  label.text=comicLoadingStage+size+"\n경과 "+seconds+"초"
 }
 private fun showComicLoading(stage:String,bytes:Long=-1,total:Long=-1){
  runOnUiThread {
   if(finished)return@runOnUiThread
   comicLoadingStage=stage
   comicLoadingBytes=bytes
   comicLoadingTotal=total
   updateComicLoadingText()
  }
 }
 private fun finishComicLoading(){
  mainHandler.removeCallbacks(comicLoadingTicker)
  comicLoadingStage=""
  comicLoadingView=null
 }
 private var txtView:TextView?=null
 private var comicBitmap:Bitmap?=null
 private var pdfBitmap:Bitmap?=null
 private var pdfQualityPage=-1
 private var pdfRenderedPage=-1
 private var pdfQualityBoost=1f
 private val streamedComicId get()=intent.getStringExtra("streamComicBookId").orEmpty()
 private val streamedSourceId get()=intent.getStringExtra("streamComicSourceId").orEmpty()
 private fun streamedComic()=streamedSourceId.isNotEmpty()&&streamedComicId.isNotEmpty()
 private var speechEmptyPages=0
 private var tmp:File?=null
 private var spine=emptyList<String>()
 private var image:ImageView?=null
 private class PageWebView(context:android.content.Context):WebView(context){
  fun pageCount():Int{
   val viewport=width.coerceAtLeast(1)
   val extent=computeHorizontalScrollRange().coerceAtLeast(viewport)
   return ((extent.toLong()+viewport-1)/viewport).toInt().coerceIn(1,10000)
  }
 }
 private var web:PageWebView?=null
 private var indexWeb:PageWebView?=null
 private var chapterPages=IntArray(0)
 private var indexEpoch=0
 private var pageReady=false
 private lateinit var body:FrameLayout
 private lateinit var column:LinearLayout
 private lateinit var progress:TextView
 private lateinit var playbackButton:Button
 private lateinit var header:LinearLayout
 private lateinit var menu:LinearLayout
 private val prefs by lazy { getSharedPreferences("crema_reader", MODE_PRIVATE) }
 private fun dp(n:Int)=((n*resources.displayMetrics.density)+0.5f).toInt()
 private fun dark()=GlobalConfig.isDark(this)
 private fun design()=ReaderDesign.tokens(this)
 // Explicit sepia/dark reading pages remain user-controlled; chrome uses the
 // selected Heyclicky/Cobalt palette regardless of those reading preferences.
 // iFLYTEK AINOTE 2: E-Ink favors black text on an untextured white page.
 private fun bg()=if(GlobalConfig.isEInk(this))Color.WHITE else ReaderBackgrounds.get(backgroundChoice).bg
  .takeIf{it.isNotBlank()}?.let(Color::parseColor)?:design().bg()
 private fun fg()=if(GlobalConfig.isEInk(this))Color.BLACK else ReaderBackgrounds.get(backgroundChoice).fg
  .takeIf{it.isNotBlank()}?.let(Color::parseColor)?:design().fg()
 private fun chromeColor()=design().card()
 private fun chromeText()=design().fg()
 private fun twoPages()=dualPageWide&&resources.configuration.screenWidthDp>=600
 private fun isComic()=format=="zip"||format=="cbz"
 private fun comicSpread():Boolean {
  val config=resources.configuration
  return ComicReadingPolicy.spread(comicDirection,comicTwoPage,
   config.screenWidthDp,config.screenHeightDp)
 }
 private fun styleButton(view:Button,primary:Boolean=false) {
  val colors=design()
  val fill=if(primary)colors.brand() else colors.tint()
  val stroke=if(primary)colors.brand() else colors.outline()
  view.setTextColor(if(primary)colors.brandText() else colors.tintText())
  val bg=ReaderDesign.shape(this,colors,fill,stroke,12f)
  view.background=RippleDrawable(
   ColorStateList.valueOf(if(GlobalConfig.isEInk(this))Color.TRANSPARENT else if(dark())Color.argb(52,191,218,255) else Color.argb(32,36,92,255)),
   bg,ReaderDesign.shape(this,colors,Color.WHITE,Color.WHITE,12f))
  ReaderDesign.flatButton(view)
  view.typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
  view.isAllCaps=false
  view.minHeight=dp(44)
  view.minimumWidth=0
  view.minWidth=0
  view.setPadding(dp(8),0,dp(8),0)
 }
 private fun button(label:String, onTap:()->Unit)=Button(this).apply {
  text=label;textSize=13f
  styleButton(this)
  setOnClickListener{onTap()}
 }
 private fun spaceButtons(vararg rows:LinearLayout) {
  rows.forEach{row->
   for(i in 0 until row.childCount){
    val child=row.getChildAt(i)
    if(child !is Button && child !is android.widget.ImageButton)continue
    val params=child.layoutParams as? LinearLayout.LayoutParams ?:continue
    params.marginStart=dp(3)
    params.marginEnd=dp(3)
    child.layoutParams=params
   }
  }
 }
 override fun onCreate(state:Bundle?){
  setTheme(if(GlobalConfig.isDark(this))
   com.ckchoi.elibrary.common.R.style.Theme_CkReader_Cobalt
   else com.ckchoi.elibrary.common.R.style.Theme_CkReader_Heyclicky)
  super.onCreate(state)
  val filter=IntentFilter(ReaderTtsNotificationService.ACTION_COMMAND)
  if(android.os.Build.VERSION.SDK_INT>=33)
   registerReceiver(ttsNotificationReceiver,filter,Context.RECEIVER_NOT_EXPORTED)
  else registerReceiver(ttsNotificationReceiver,filter)
  ttsReceiverRegistered=true
  src=intent.data
  format=intent.getStringExtra("format").orEmpty().lowercase(Locale.ROOT)
  if(src==null||format !in setOf("pdf","epub","txt","md","md","zip","cbz")){finish();return}
  com.ckchoi.elibrary.common.ReaderDocumentSession.open(this,intent.getStringExtra("documentId").orEmpty())
  docId=MessageDigest.getInstance("SHA-256").digest(intent.getStringExtra("documentId").orEmpty().ifBlank{src.toString()}.toByteArray()).take(12).joinToString(""){"%02x".format(it)}
  intent.getStringExtra("libraryBookId")?.let { getSharedPreferences("ckreader_library",MODE_PRIVATE).edit().putLong("last_"+it,System.currentTimeMillis()).apply() }
  loadSettings()
  if(format=="epub")pendingSpreadIndex=intent.getIntExtra("restoreSpread",prefs.getInt("spread_"+docId,0)).coerceAtLeast(0)
  intent.removeExtra("restoreSpread")
  lastSpreadMode=twoPages()
  lastComicSpreadMode=comicSpread()
  drawScreen()
  when(format){
   "pdf"->openPdf()
   "epub"->openEpub()
   "txt","md"->openText()
   "zip","cbz"->openComic()
  }
 }
 private fun drawScreen(){
  androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
  window.statusBarColor=design().bg();window.navigationBarColor=design().bg()
  window.decorView.systemUiVisibility=if(dark())0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
  column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(chromeColor())}
  androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(column){view,insets->
   val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
   if(isComic()&&comicFullscreen)view.setPadding(0,0,0,0)
   else view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
   androidx.core.view.WindowInsetsCompat.CONSUMED
  }
  header=LinearLayout(this).apply{
   gravity=Gravity.CENTER_VERTICAL;orientation=LinearLayout.HORIZONTAL
   setBackgroundColor(chromeColor())
   setPadding(dp(10),dp(5),dp(10),dp(5))
  }
  header.addView(button("‹ 뒤로"){finish()},LinearLayout.LayoutParams(dp(85),dp(48)))
  val title=TextView(this).apply{
   text=when(format){"pdf"->"PDF 뷰어";"epub"->"EPUB 뷰어";"txt"->"TXT 뷰어";"md"->"Markdown Viewer";"cbz"->"CBZ 뷰어";else->"ZIP 이미지 뷰어"}
   gravity=Gravity.CENTER;textSize=17f;setTextColor(chromeText())
   typeface=Typeface.create("sans-serif",Typeface.BOLD)
  }
  header.addView(title,LinearLayout.LayoutParams(0,dp(48),1f))
  // Page information now occupies the top-right header slot previously
  // used by the light/dark theme name. There is no separate footer bar.
  progress=TextView(this).apply{
   gravity=Gravity.CENTER; textSize=11f
   setTextColor(design().secondary())
   typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
   contentDescription="현재 읽기 페이지. 눌러 이동할 페이지 입력"
   setOnClickListener{showPageJumpDialog()}
   setOnLongClickListener{
    if(ttsPlaying||ttsPaused){returnToSpeechPosition();true}else false
   }
   maxLines=2
  }
  header.addView(progress,LinearLayout.LayoutParams(dp(124),dp(48)))
  column.addView(header,LinearLayout.LayoutParams(-1,dp(58)))
  body=FrameLayout(this).apply{setBackgroundColor(bg())}
  body.addOnLayoutChangeListener{_,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom->
   val resized=right-left!=oldRight-oldLeft || bottom-top!=oldBottom-oldTop
   if(resized && isComic() && total>0 && !finished)body.post {
    if(!finished) {
     if(comicDirection==ComicReadingPolicy.Direction.VERTICAL)
      comicVerticalList?.relayoutAt(page)
     else renderComic()
    }
   }
  }
  column.addView(body,LinearLayout.LayoutParams(-1,0,1f))
  showMessage("문서를 여는 중입니다...")
  menu=LinearLayout(this).apply{
   orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER
   setBackgroundColor(chromeColor())
   setPadding(dp(5),dp(2),dp(5),dp(6))
  }
  if(format in setOf("pdf","epub","txt","md")) {
   menu.addView(button("TTS"){showListening()}.apply{styleButton(this,true)},
    LinearLayout.LayoutParams(0,dp(48),1f))
   playbackButton=button("재생") {
    if(ttsPlaying)stopSpeaking() else resumeSpeaking()
   }
   menu.addView(playbackButton,LinearLayout.LayoutParams(0,dp(48),1f))
   updateListeningAction()
  }
  menu.addView(button("목차"){showContents()},LinearLayout.LayoutParams(0,dp(48),1f))
  menu.addView(button("보기"){showStyleSettings()},LinearLayout.LayoutParams(0,dp(48),1f))
  menu.addView(button("화면"){showScreenSettings()},LinearLayout.LayoutParams(0,dp(48),1f))
  column.addView(menu,LinearLayout.LayoutParams(-1,dp(58)))
  spaceButtons(header,menu)
  ReaderDesign.finishButtons(column)
  setContentView(column)
  applyWindowSettings()
  androidx.core.view.ViewCompat.requestApplyInsets(column)
 }
 private fun showMessage(message:String){
  body.removeAllViews()
  val label=TextView(this).apply{
   text=message;gravity=Gravity.CENTER;textSize=14f;setTextColor(fg());setPadding(dp(20),0,dp(20),0)
  }
  comicLoadingView=label
  body.addView(label,FrameLayout.LayoutParams(-1,-1))
 }
 private fun fail(message:String)=runOnUiThread{if(!finished)showMessage(message)}
 private fun savePage(){
  val edit=prefs.edit().putInt("index_"+docId,page)
  if(format=="epub")edit.putInt("spread_"+docId,spreadIndex)
  edit.apply()
 }
 private fun updateProgress(){
  progress.text=if(isComic())ComicReadingPolicy.positionLabel(page,total,comicSpread())
  else if(format=="pdf"&&twoPages()){
   val end=min(page+2,total);"${page+1}–$end / $total"
  }else if(format=="epub"){
   val counts=chapterPages
   if(counts.size==total&&counts.all{it>0}){
    val position=counts.take(page).sum()+spreadIndex.coerceIn(0,counts[page]-1)+1
    "$position / ${counts.sum()}"
   }else{
    val count=counts.getOrNull(page)?.takeIf{it>0}
    if(count==null)"페이지 계산 중" else "${page+1}장 · ${spreadIndex+1}/$count · 계산 중"
   }
  }else "${page+1} / $total"
  if(ttsPlaying)progress.text="낭독 중 · "+progress.text
  progress.setTextColor(if(ttsPlaying)design().brand() else design().secondary())
  progress.contentDescription=if(ttsPlaying||ttsPaused)
   "현재 읽기 페이지. 눌러 페이지 이동, 길게 눌러 낭독 위치로 이동"
   else "현재 읽기 페이지. 눌러 이동할 페이지 입력"
  progress.visibility=if(showPageInfo||ttsPlaying)View.VISIBLE else View.INVISIBLE
 }
 private fun showPageJumpDialog(){
  if(total<=0)return
  val counts=if(format=="epub")chapterPages.takeIf{it.size==total&&it.all{count->count>0}}?.copyOf() else null
  if(format=="epub"&&counts==null){
   Toast.makeText(this,"전체 페이지를 계산하는 중입니다. 잠시 후 다시 시도해주세요.",Toast.LENGTH_SHORT).show()
   return
  }
  val lastPage=counts?.sum()?:total
  val currentPage=if(counts!=null)counts.take(page).sum()+spreadIndex+1 else page+1
  val input=EditText(this).apply{
   inputType=InputType.TYPE_CLASS_NUMBER
   setSingleLine(true)
   setText(currentPage.toString())
   selectAll()
   hint="1 ~ $lastPage"
  }
  val container=FrameLayout(this).apply{
   setPadding(dp(24),0,dp(24),0)
   addView(input,FrameLayout.LayoutParams(-1,-2))
  }
  val dialog=AlertDialog.Builder(this).setTitle("페이지 이동")
   .setMessage("이동할 페이지를 입력하세요. (1 ~ $lastPage)")
   .setView(container)
   .setNegativeButton("취소",null)
   .setPositiveButton("이동",null)
   .create()
  dialog.show()
  dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
   val number=input.text.toString().toIntOrNull()
   if(number==null||number !in 1..lastPage){
    input.error="1 ~ $lastPage 사이의 페이지를 입력하세요."
    return@setOnClickListener
   }
   dialog.dismiss()
   if(counts==null)showPage(number-1)
   else {
    var chapter=0
    var offset=number-1
    while(offset>=counts[chapter]){offset-=counts[chapter];chapter++}
    pendingLastSpread=false
    if(ttsPlaying)speechFollowing=false
    if(chapter==page&&pageReady){
     if(spreadIndex!=offset){
      clearSpeechHighlight()
      spreadIndex=offset
      web?.scrollTo(offset*(web?.width?:0),0)
      savePage();updateProgress()
     }
    }else{
     pendingSpreadIndex=offset
     showPage(chapter)
    }
   }
  }
  input.requestFocus()
  dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
 }
 private fun navigate(direction:Int){
  if(ttsPlaying){speechFollowing=false;clearSpeechHighlight()}
  if(isComic()){
   if(comicDirection==ComicReadingPolicy.Direction.VERTICAL)
    comicVerticalList?.moveViewport(direction)
   else if(total>0) {
    beginPageTurn(direction)
    showPage(ComicReadingPolicy.move(page,direction,total,comicSpread()))
   }
   return
  }
  if(format!="epub"){
   if(page+direction*(if(format=="pdf"&&twoPages())2 else 1) in 0 until total)
    beginPageTurn(direction)
   showPage(page+direction*(if(format=="pdf"&&twoPages())2 else 1));return
  }
  val view=web
  if(view==null||!pageReady)return
  val count=chapterPages.getOrNull(page)?.coerceAtLeast(1)?:return
  val target=spreadIndex+direction
  if(target in 0 until count){
   beginPageTurn(direction)
   spreadIndex=target
   view.scrollTo(target*view.width,0)
   savePage();updateProgress()
   if(ttsPlaying&&!speechFollowing)reconnectSpeechHighlightIfVisible()
   return
  }
  pendingLastSpread=direction<0&&page+direction>=0
  if(page+direction in 0 until total)beginPageTurn(direction)
  showPage(page+direction)
 }
 private fun beginPageTurn(direction:Int) {
  if(GlobalConfig.isEInk(this)||pageTurnMs<=0||body.width<=0||body.height<=0||
   body.width.toLong()*body.height>8_000_000L)return
  clearPageTurn()
  val bitmap=runCatching{
   Bitmap.createBitmap(body.width,body.height,Bitmap.Config.ARGB_8888).also{
    body.draw(Canvas(it))
   }
  }.getOrNull()?:return
  val overlay=PaperPageTurnView(this,bitmap,direction>0,bg())
  body.addView(overlay,FrameLayout.LayoutParams(-1,-1))
  pageTurnOverlay=overlay
  pageTurnAnimator=ValueAnimator.ofFloat(0f,1f).apply {
   duration=pageTurnMs.toLong()
   interpolator=android.view.animation.AccelerateDecelerateInterpolator()
   addUpdateListener{overlay.progress=it.animatedValue as Float}
   addListener(object:AnimatorListenerAdapter(){
    override fun onAnimationEnd(animation:Animator){
     if(pageTurnOverlay===overlay)clearPageTurn()
    }
   })
   start()
  }
 }
 private fun clearPageTurn(){
  pageTurnAnimator?.let{it.removeAllListeners();it.cancel()}
  pageTurnAnimator=null
  pageTurnOverlay?.let{body.removeView(it);it.dispose()}
  pageTurnOverlay=null
 }
 private fun showPage(index:Int,fromSpeech:Boolean=false){
  if(index<0||index>=total)return
  val target=when {
   format=="pdf"&&twoPages()->index-index%2
   isComic()->ComicReadingPolicy.first(index,total,comicSpread())
   else->index
  }
  if(target!=page&&ttsPlaying&&!fromSpeech)speechFollowing=false
  if(target!=page)clearSpeechHighlight()
  page=target
  if(format=="epub"){spreadIndex=0;pageReady=false}
  lastChapterNavigation=System.currentTimeMillis()
  updateProgress()
  savePage()
  when(format){
   "pdf"->renderPdf()
   "txt","md"->renderText()
   "zip","cbz"->{
    if(comicDirection==ComicReadingPolicy.Direction.VERTICAL)
     comicVerticalList?.scrollToPage(page)
    else renderComic()
   }
   "epub"->web?.loadUrl(spineUrl(page))
  }
  pageTurnOverlay?.let{overlay->
   if(overlay.parent==null)body.addView(overlay,FrameLayout.LayoutParams(-1,-1))
  }
 }
 private fun setupImagePage() {
  if(isComic()){
   // Switch between full-screen horizontal and the bounded three-page list
   // without losing the current page or keeping old bitmaps allocated.
   generation++
   comicVerticalList?.dispose()
   comicVerticalList=null
   (image as? ZoomablePageView)?.setPageBitmap(null)
   image=null
   comicBitmap?.recycle()
   comicBitmap=null
   if(comicDirection==ComicReadingPolicy.Direction.VERTICAL) {
    val list=ComicVerticalReader(this,total,page,
     decode={index,width,height->decodeComicPage(index,width,height)},
     onCurrentPage={index->
      if(!finished&&comicDirection==ComicReadingPolicy.Direction.VERTICAL&&page!=index){
       page=index
       savePage()
       updateProgress()
      }
     },
     onCenterTap={showComicFullscreenAction()},
     onPageError={index,error->
      android.util.Log.w("CremaReader","Webtoon page "+index+" decode failed",error)
      if(streamedComic()&&!comicStreamFallbackAttempted&&!finished) {
       comicStreamFallbackAttempted=true
       intent.removeExtra("streamComicSourceId")
       intent.removeExtra("streamComicBookId")
       intent.removeExtra("streamComicPageCount")
       showMessage("OPDS 이미지 처리 실패 · ZIP 원본으로 전환합니다.")
       openComic()
      }
     })
    comicVerticalList=list
    body.removeAllViews()
    body.addView(list,FrameLayout.LayoutParams(-1,-1))
    return
   }
  }
  val pic=ZoomablePageView(this).apply {
   pagingByTouch=touchPaging
   setBackgroundColor(if(dark())Color.rgb(13,35,64) else Color.rgb(234,242,255))
   onPageRequest={direction->navigate(direction)}
   if(isComic())onCenterTap={showComicFullscreenAction()}
   textHoldEnabled={!finished&&format=="pdf"&&ttsPlaying&&pdfRenderedPage==page}
   onTextHold={x,y->
    if(format=="pdf"&&ttsPlaying&&pdfRenderedPage==page){speakAtPdfTap(x,y);true}else false
   }
   onMagnificationChanged={factor->
    if(format=="pdf"&&total>0){
     val requested=when {
      factor>=3.5f->2.8f
      factor>=2f->2.2f
      else->1.6f
     }
     if(pdfQualityPage!=page||pdfQualityBoost+0.05f<requested) {
      pdfQualityPage=page
      pdfQualityBoost=requested
      renderPdf(requested,preserveZoom=true)
     }
    }
   }
   onVerticalSwipe={dy->
    if(gestureBrightness){
     brightness=(brightness-dy.toInt()/dp(8)).coerceIn(10,100)
     systemBrightness=false;saveSettings()
     window.attributes=window.attributes.apply{screenBrightness=brightness/100f}
    }
   }
  }
  image=pic
  body.removeAllViews()
  body.addView(pic,FrameLayout.LayoutParams(-1,-1))
 }
 private fun speakAtPdfTap(x:Float,y:Float) {
  val base=page
  val spread=twoPages()
  val region=pdfSpeechRegions.firstOrNull{it.page in base..(base+if(spread)1 else 0)&&it.bounds.contains(x,y)}?:return
  val selected=region.page
  val relativeX=((x-region.bounds.left)/region.bounds.width()).coerceIn(0f,1f)
  val relativeY=((y-region.bounds.top)/region.bounds.height()).coerceIn(0f,1f)
  stopSpeaking()
  val sequence=speechSequence
  listeningAction?.text="선택 위치 인식 중..."
  worker.execute {
   val text=runCatching {
    val extractor=pdfSpeech ?: PdfSpeechExtractor(applicationContext,src!!).also{pdfSpeech=it}
    buildString {
     append(extractor.extractFromPosition(selected,pdf!!,relativeX,relativeY))
     if(spread&&selected==base&&base+1<total){append("\n\n");append(extractor.extract(base+1,pdf!!))}
    }
   }.getOrDefault("")
   mainHandler.post {
    if(finished||page!=base||sequence!=speechSequence)return@post
    if(text.isBlank()){
     updateListeningAction()
     Toast.makeText(this,"선택 위치의 글자를 인식하지 못했습니다.",Toast.LENGTH_SHORT).show()
    }else{requestedPdfSpeech=text;startSpeaking()}
   }
  }
 }

 private fun openPdf(){
  try {
   pdfFd=contentResolver.openFileDescriptor(src!!,"r")?:throw IllegalArgumentException()
   pdf=PdfRenderer(pdfFd!!)
   total=pdf!!.pageCount
   if(total<1)throw IllegalArgumentException()
   page=prefs.getInt("index_"+docId,0).coerceIn(0,total-1)
   setupImagePage()
   showPage(page)
  }catch(_:Exception){showMessage("PDF를 열 수 없습니다.\n암호로 보호되거나 지원하지 않는 파일일 수 있습니다.")}
 }
 private fun renderPdf(qualityBoost:Float=1f,preserveZoom:Boolean=false){
  val pageIndex=page
  if(!preserveZoom){pdfQualityPage=pageIndex;pdfQualityBoost=1f}
  val spread=twoPages()
  val viewportWidth=body.width.takeIf{it>0}?:resources.displayMetrics.widthPixels
  val viewportHeight=body.height.takeIf{it>0}?:resources.displayMetrics.heightPixels
  val job=++generation
  worker.execute {
   if(finished||job!=generation)return@execute
   var bitmap:Bitmap?=null
   val regions=ArrayList<PdfSpeechRegion>()
   try {
    if(spread){
     var bw=(viewportWidth*1.25f*qualityBoost).toInt().coerceIn(480,4800)
     var bh=(viewportHeight*1.25f*qualityBoost).toInt().coerceIn(480,5500)
     val pixels=bw.toLong()*bh
     if(pixels>16_000_000L){
      val ratio=kotlin.math.sqrt(16_000_000.0/pixels).toFloat()
      bw=(bw*ratio).toInt().coerceAtLeast(480)
      bh=(bh*ratio).toInt().coerceAtLeast(480)
     }
     bitmap=Bitmap.createBitmap(bw,bh,Bitmap.Config.ARGB_8888)
     bitmap!!.eraseColor(Color.WHITE)
     val slotWidth=bw/2
     for(side in 0..1){
      val targetPage=pageIndex+side
      if(targetPage>=total)break
      pdf!!.openPage(targetPage).use{p->
       val margin=(14*resources.displayMetrics.density*qualityBoost).toInt().coerceIn(10,42)
       val availableW=(slotWidth-2*margin).coerceAtLeast(1)
       val availableH=(bh-2*margin).coerceAtLeast(1)
       val scale=min(availableW.toFloat()/p.width,availableH.toFloat()/p.height)
       val pageWidth=(p.width*scale).toInt().coerceAtLeast(1)
       val pageHeight=(p.height*scale).toInt().coerceAtLeast(1)
       val left=side*slotWidth+(slotWidth-pageWidth)/2
       val top=(bh-pageHeight)/2
       regions.add(PdfSpeechRegion(targetPage,RectF(left.toFloat()/bw,top.toFloat()/bh,(left+pageWidth).toFloat()/bw,(top+pageHeight).toFloat()/bh)))
       p.render(bitmap!!,Rect(left,top,left+pageWidth,top+pageHeight),null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
      }
     }
     Canvas(bitmap!!).drawLine(slotWidth.toFloat(),0f,slotWidth.toFloat(),bh.toFloat(),
      Paint().apply{color=Color.LTGRAY;strokeWidth=2f})
    }else pdf!!.openPage(pageIndex).use { p ->
      val baseW=viewportWidth.coerceAtLeast(360)
      val baseH=viewportHeight.coerceAtLeast(360)
      val wanted=min(baseW.toFloat()*qualityBoost/p.width,
       baseH.toFloat()*qualityBoost/p.height)
      val maximum=min(4800f/p.width,5500f/p.height)
      val areaLimit=kotlin.math.sqrt(16_000_000f/(p.width.toFloat()*p.height))
      val scale=min(wanted,min(maximum,areaLimit)).coerceAtLeast(.0001f)
      val bw=(p.width*scale).toInt().coerceAtLeast(1)
      val bh=(p.height*scale).toInt().coerceAtLeast(1)
      bitmap=Bitmap.createBitmap(bw,bh,Bitmap.Config.ARGB_8888)
      bitmap!!.eraseColor(Color.WHITE)
      regions.add(PdfSpeechRegion(pageIndex,RectF(0f,0f,1f,1f)))
      p.render(bitmap!!,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
     }
    runOnUiThread {
     if(finished||job!=generation)bitmap?.recycle()
     else {
      val previous=pdfBitmap
      (image as? ZoomablePageView)?.setPageBitmap(bitmap,preserveZoom=preserveZoom)
      pdfBitmap=bitmap
      pdfSpeechRegions=regions
      pdfRenderedPage=pageIndex
      if(previous!==bitmap)previous?.recycle()
     }
    }
   }catch(e:Exception){
    bitmap?.recycle()
    android.util.Log.e("CremaReader","PDF page render error",e)
    if(job==generation&&!finished)fail("PDF 페이지를 렌더링할 수 없습니다.")
   }
  }
 }

 private fun openText() {
  worker.execute {
   try {
    val input=contentResolver.openInputStream(src!!)?:error("TXT 파일을 열 수 없습니다.")
    val content=ReaderContentSupport.readText(input)
    val pages=ReaderContentSupport.textPages(content,if(format=="md")6000 else 2400)
    runOnUiThread {
     if(finished)return@runOnUiThread
     txtPages=pages
     total=pages.size
     page=prefs.getInt("index_"+docId,0).coerceIn(0,total-1)
     val text=TextView(this).apply {
      setTextColor(fg())
      setTextIsSelectable(true)
      setBackgroundColor(bg())
      val hold=SentenceHoldGesture(this,mainHandler,{!finished&&ttsPlaying},{x,y->
        val layout=this.layout
        if(layout!=null) {
         val line=layout.getLineForVertical((y.toInt()+scrollY-totalPaddingTop).coerceAtLeast(0))
         val offset=layout.getOffsetForHorizontal(line,x-totalPaddingLeft)
         requestedSpeechOffset=SpeechPosition.sentenceStart(this.text.toString(),offset)
         stopSpeaking()
         startSpeaking()
        }
      })
      setOnTouchListener { view,event ->
       val held=hold.onTouch(event)
       if(event.actionMasked==MotionEvent.ACTION_UP&&!held)view.performClick()
       held||ttsPlaying
      }
     }
     val scroll=ScrollView(this).apply {
      isFillViewport=true
      setBackgroundColor(bg())
      addView(text,FrameLayout.LayoutParams(-1,-2))
     }
     txtView=text
     body.removeAllViews()
     body.addView(scroll,FrameLayout.LayoutParams(-1,-1))
     showPage(page)
    }
   }catch(e:Exception){
    android.util.Log.e("CremaReader","TXT open failed",e)
    fail("TXT 파일을 열 수 없습니다. 인코딩과 파일 크기를 확인하세요.")
   }
  }
 }
 private fun markdownSpanned(markdown:String):android.text.Spanned {
  val escaped=android.text.TextUtils.htmlEncode(markdown)
  val lines=escaped.lines().map { line ->
   when {
    line.startsWith("### ")->"<h3>"+line.drop(4)+"</h3>"
    line.startsWith("## ")->"<h2>"+line.drop(3)+"</h2>"
    line.startsWith("# ")->"<h1>"+line.drop(2)+"</h1>"
    line.startsWith("- ")->"&#8226; "+line.drop(2)
    else->line
   }
  }.joinToString("<br>")
  val styled=lines.replace(Regex("\\*\\*(.+?)\\*\\*"),"<b>$1</b>")
   .replace(Regex("(?<!\\*)\\*(?!\\*)([^*]+)\\*"),"<i>$1</i>")
   .replace(Regex("`([^`]+)`"),"<tt>$1</tt>")
  return Html.fromHtml(styled,Html.FROM_HTML_MODE_COMPACT)
 }
 private fun renderText() {
  val view=txtView ?:return
  if(page !in txtPages.indices)return
  view.setTextColor(fg())
  view.setBackgroundColor(bg())
  view.textSize=(zoom/100f*17f).coerceIn(12f,34f)
  val selectedTypeface=when(fontChoice){
   1->Typeface.SERIF
   2->Typeface.SANS_SERIF
   else->ReaderFonts.family(this,fontChoice)?.let{ReaderFonts.font(this,it.id)}
    ?:Typeface.DEFAULT
  }
  view.setLineSpacing(0f,(lineHeight/100f).coerceIn(1f,2.4f))
  view.setPadding(dp(hMargin.coerceAtLeast(12)),dp(vMargin.coerceAtLeast(12)),dp(hMargin.coerceAtLeast(12)),dp(vMargin.coerceAtLeast(12)))
  view.gravity=when(alignmentChoice){
   1->Gravity.TOP or Gravity.LEFT
   2->Gravity.TOP or Gravity.CENTER_HORIZONTAL
   else->Gravity.TOP or Gravity.START
  }
  val text=if(format=="md")android.text.SpannableStringBuilder(
   markdownSpanned(txtPages[page])).apply {
    if(fontChoice!=0) {
     // Markdown's <tt> span otherwise overrides the chosen reading typeface.
     getSpans(0,length,android.text.style.TypefaceSpan::class.java)
      .forEach(::removeSpan)
    }
   } else txtPages[page]
  view.text=text
  view.typeface=Typeface.create(selectedTypeface,if(fontBold)Typeface.BOLD else Typeface.NORMAL)
  view.requestLayout()
  (view.parent as? ScrollView)?.post{(view.parent as? ScrollView)?.scrollTo(0,0)}
 }
 private fun openComic() {
  comicLoadingStarted=android.os.SystemClock.elapsedRealtime()
  mainHandler.removeCallbacks(comicLoadingTicker)
  showComicLoading("ZIP/CBZ 파일 열기 준비 중")
  mainHandler.post(comicLoadingTicker)
  if(streamedComic()){
   val count=intent.getIntExtra("streamComicPageCount",0)
   if(count in 1..50_000) {
    total=count
    page=prefs.getInt("index_"+docId,0).coerceIn(0,total-1)
    finishComicLoading()
    setupImagePage()
    showPage(page)
    return
   }
   // A missing OPDS-PSE count must not block a valid ZIP book. The same
   // Book Oasis content://book URI also provides an original ZIP pipe.
   intent.removeExtra("streamComicSourceId")
   intent.removeExtra("streamComicBookId")
   intent.removeExtra("streamComicPageCount")
   showComicLoading("OPDS 페이지 수 없음 · ZIP 원본 분석으로 전환")
  }
  worker.execute {
   // Files acquired by Book Oasis and other remotes already live in app cache.
   // Opening the existing ZIP directly avoids a second multi-gigabyte copy.
   if(src?.scheme=="file") {
    showComicLoading("저장된 ZIP/CBZ 페이지 목록 분석 중")
    var opened:ZipFile?=null
    try {
     val original=File(requireNotNull(src?.path)).canonicalFile
     val cacheRoot=cacheDir.canonicalFile
     require(original.path.startsWith(cacheRoot.path+File.separator)&&original.isFile){
      "앱이 관리하는 파일이 아닙니다."
     }
     val archive=ZipFile(original)
     opened=archive
     val pages=ReaderContentSupport.pages(archive)
     require(pages.isNotEmpty()){"ZIP/CBZ 내부에서 이미지 페이지를 찾지 못했습니다."}
     runOnUiThread {
      if(finished){runCatching{archive.close()};return@runOnUiThread}
      finishComicLoading()
      zip=archive;comicPages=pages;total=pages.size
      page=prefs.getInt("index_"+docId,0).coerceIn(0,total-1)
      setupImagePage();showPage(page)
     }
    }catch(e:Exception) {
     runCatching{opened?.close()}
     if(!finished)fail("ZIP/CBZ 파일을 열 수 없습니다: "+(e.message?:"압축 오류").take(120))
    }
    return@execute
   }
   if(ArchiveRangeSource.matches(src!!)) {
    showComicLoading("원격 ZIP/CBZ 파일 크기 확인 중")
    var channel:RangeReadChannel?=null
    var opened:SeekZip?=null
    try {
     var received=0L
     val remote=ArchiveRangeSource.open(contentResolver,src!!){bytes->
      received+=bytes
      showComicLoading("원격 ZIP/CBZ 인덱스 읽는 중",received)
     }
     showComicLoading("원격 ZIP/CBZ 중앙 디렉터리 분석 중",received)
     channel=remote
     comicRangeChannel=remote
     if(finished){remote.close();return@execute}
     val ready=SeekZip(remote)
     opened=ready
     val pages=ReaderContentSupport.pages(ready)
     require(pages.isNotEmpty()) {"ZIP/CBZ 안에 이미지가 없습니다."}
     runOnUiThread {
      if(finished){remote.close();runCatching{ready.close()};return@runOnUiThread}
      finishComicLoading()
      comicSeekZip=ready
      comicPages=pages
      total=pages.size
      page=prefs.getInt("index_"+docId,0).coerceIn(0,total-1)
      setupImagePage()
      showPage(page)
     }
    }catch(e:Exception) {
     channel?.close()
     comicRangeChannel=null
     runCatching{opened?.close()}
     android.util.Log.w("CremaReader","Remote ZIP Range failed; falling back to full archive",e)
     if(!finished)
      showComicLoading("ZIP 부분 읽기 실패 · 원본 전체 다운로드로 전환 중")
     // Drive/GDS and Book Oasis provider URIs can stream the original ZIP
     // through a pipe; do not stop on Range errors.
    }
    if(finished)return@execute
   }
   // Seekable SAF files and virtual remote archives can be read by ZIP central
   // directory offset without copying the full ZIP/CBZ to local storage.
   showComicLoading("ZIP/CBZ 파일 인덱스 확인 중")
   val isRawOpds=src?.authority=="com.ckchoi.ckreader.stream" &&
    src?.pathSegments?.firstOrNull()=="book"
   val descriptor=if(isRawOpds||ArchiveRangeSource.matches(src!!))null
    else runCatching{contentResolver.openFileDescriptor(src!!,"r")}.getOrNull()
   if(descriptor!=null){
    var opened:SeekZip?=null
    try {
     val channel=FileInputStream(descriptor.fileDescriptor).channel
     val ready=SeekZip(channel)
     opened=ready
     val pages=ReaderContentSupport.pages(ready)
     require(pages.isNotEmpty()){"ZIP/CBZ 안에 이미지가 없습니다."}
     runOnUiThread {
      if(finished){runCatching{ready.close()};runCatching{descriptor.close()};return@runOnUiThread}
      finishComicLoading()
      comicSeekZip=ready
      comicSeekFd=descriptor
      comicPages=pages
      total=pages.size
      page=prefs.getInt("index_"+docId,0).coerceIn(0,total-1)
      setupImagePage()
      showPage(page)
     }
     return@execute
    }catch(e:Exception){
     android.util.Log.w("CremaReader","Seekable comic reader fallback",e)
     runCatching{opened?.close()}
     runCatching{descriptor.close()}
    }
   }
   var opened:ZipFile?=null
   var target:File?=null
   try {
    showComicLoading("원본 ZIP/CBZ 다운로드 준비 중")
    val localFile=File.createTempFile("comic-",".zip",File(cacheDir,"comic-reader").apply{mkdirs()})
    target=localFile
    tmp=localFile
    val input=contentResolver.openInputStream(src!!)?:error("압축 파일을 읽을 수 없습니다.")
    input.use {stream->localFile.outputStream().use { output->
     val buffer=ByteArray(65536);var size=0L;var last=0L
     val knownSize=intent.getLongExtra("bookFileSize",-1L).takeIf{it>0}?:-1L
     while(true){
      if(Thread.currentThread().isInterrupted)
       throw java.io.InterruptedIOException("도서 다운로드 취소")
      val n=stream.read(buffer);if(n<0)break;if(n==0)continue
      size+=n
      require(size<=20L*1024L*1024L*1024L){"압축파일 크기 상한은 20GB입니다."}
      val now=android.os.SystemClock.elapsedRealtime()
      if(now-last>=350L){
       showComicLoading("원본 ZIP/CBZ 다운로드 중",size,knownSize)
       last=now
      }
      if(size%(32L*1024L*1024L)<n && localFile.parentFile.usableSpace<64L*1024L*1024L)
       error("임시 ZIP/CBZ 복사에 필요한 저장 공간이 부족합니다.")
      output.write(buffer,0,n)
     }
    }}
    showComicLoading("ZIP/CBZ 다운로드 완료 · 압축파일 페이지 분석 중")
    val ready=ZipFile(localFile);opened=ready
    val pages=ReaderContentSupport.pages(ready)
    require(pages.isNotEmpty()){"ZIP/CBZ 파일 안에 지원하는 이미지가 없습니다."}
    runOnUiThread{
     if(finished){ready.close();localFile.delete();return@runOnUiThread}
     finishComicLoading()
     zip=ready
     comicPages=pages
     total=pages.size
     page=prefs.getInt("index_"+docId,0).coerceIn(0,total-1)
     setupImagePage()
     showPage(page)
    }
   }catch(e:Exception){
    runCatching{opened?.close()};target?.delete()
    android.util.Log.e("CremaReader","Comic open failed",e)
    fail("ZIP/CBZ 파일을 열 수 없습니다: "+(e.message?:"압축 파일 오류").take(120))
   }
  }
 }
 private fun comicPageUri(index:Int)=Uri.Builder().scheme("content")
  .authority("com.ckchoi.ckreader.stream").appendPath("page")
  .appendPath(streamedSourceId).appendPath(streamedComicId)
  .appendPath(index.toString()).build()
 private fun prefetchComic(after:Int) {
  if(!streamedComic()||finished)return
  for(index in after+1..(after+2).coerceAtMost(total-1))pagePrefetch.submit {
   if(finished)return@submit
   runCatching {
    contentResolver.openInputStream(comicPageUri(index))?.use{input->
     val buf=ByteArray(32768)
     while(!finished&&input.read(buf)!=-1)Unit
    }
   }
  }
 }
 /** Decode on demand from the active ZIP file or OPDS-PSE stream. */
 private fun decodeComicPage(index:Int,width:Int,height:Int):Bitmap {
  require(index in 0 until total){"이미지 페이지 범위가 올바르지 않습니다."}
  if(streamedComic()) {
   return ReaderContentSupport.imageBitmap({
    contentResolver.openInputStream(comicPageUri(index))
     ?:error("북오아시스 이미지 요청 실패")
   },width,height)
  }
  val fileName=comicPages.getOrNull(index)?:error("압축파일에 이미지 페이지가 없습니다.")
  val seekable=comicSeekZip
  return if(seekable!=null)ReaderContentSupport.imageBitmap(seekable,fileName,width,height)
   else ReaderContentSupport.imageBitmap(zip?:error("ZIP/CBZ를 아직 열지 못했습니다."),fileName,width,height)
 }

 /** Render one book spread without allocating two unbounded full-resolution pages. */
 private fun composeComicSpread(indices:List<Int>,width:Int,height:Int):Bitmap {
  val targetWidth=width.coerceAtLeast(1)
  val targetHeight=height.coerceAtLeast(1)
  val scale=if(targetWidth.toLong()*targetHeight>12_000_000L)
   kotlin.math.sqrt(12_000_000.0/(targetWidth.toDouble()*targetHeight)).toFloat()
   else 1f
  val bw=(targetWidth*scale).toInt().coerceAtLeast(1)
  val bh=(targetHeight*scale).toInt().coerceAtLeast(1)
  val result=Bitmap.createBitmap(bw,bh,Bitmap.Config.RGB_565)
  try {
   val canvas=Canvas(result)
   canvas.drawColor(if(comicFullscreen)Color.BLACK else bg())
   val slotWidth=bw/2f
   val paint=Paint(Paint.FILTER_BITMAP_FLAG)
   for((position,index) in indices.withIndex()) {
    val individual=decodeComicPage(index,(slotWidth*1.4f).toInt().coerceIn(400,3200),bh*2)
    try {
     val factor=min(slotWidth/individual.width.toFloat(),bh/individual.height.toFloat())
     val w=individual.width*factor
     val h=individual.height*factor
     val left=slotWidth*position+(slotWidth-w)/2f
     val top=(bh-h)/2f
     canvas.drawBitmap(individual,null,RectF(left,top,left+w,top+h),paint)
    }finally{individual.recycle()}
   }
   return result
  }catch(error:Throwable) {
   result.recycle()
   throw error
  }
 }

 private fun renderComic() {
  if(comicDirection==ComicReadingPolicy.Direction.VERTICAL)return
  val pageIndex=page
  val indices=ComicReadingPolicy.visible(pageIndex,total,comicSpread())
  if(indices.isEmpty())return
  val job=++generation
  val width=body.width.takeIf{it>0}?:resources.displayMetrics.widthPixels
  val height=body.height.takeIf{it>0}?:resources.displayMetrics.heightPixels
  worker.execute{
   if(finished||job!=generation)return@execute
   val bitmap=try {
    if(comicSpread())composeComicSpread(indices,width,height)
    else decodeComicPage(indices[0],width*2,height*2)
   }catch(e:Exception){
    android.util.Log.e("CremaReader","Comic page failed",e)
    if(streamedComic()&&!finished&&!comicStreamFallbackAttempted){
     runOnUiThread {
      if(!finished&&job==generation){
       comicStreamFallbackAttempted=true
       intent.removeExtra("streamComicSourceId")
       intent.removeExtra("streamComicBookId")
       intent.removeExtra("streamComicPageCount")
       showMessage("OPDS 페이지 스트리밍 실패 · ZIP 원본으로 전환합니다.")
       openComic()
      }
     }
    }else if(job==generation)fail("이미지를 표시할 수 없습니다: "+(e.message?:"네트워크 연결 오류").take(120))
    return@execute
   }
   runOnUiThread{
    if(finished||job!=generation){bitmap.recycle();return@runOnUiThread}
    val previous=comicBitmap
    (image as? ZoomablePageView)?.setPageBitmap(bitmap)
    comicBitmap=bitmap
    if(previous!==bitmap)previous?.recycle()
    prefetchComic(indices.last())
   }
  }
 }
 private fun normalPath(path:String):String{
  val components=ArrayList<String>()
  for(part in path.replace('\\','/').removePrefix("/").split('/')){
   when(part){
    "", "." -> Unit
    ".."->{if(components.isEmpty())throw IllegalArgumentException();components.removeAt(components.size-1)}
    else->components.add(part)
   }
  }
  return components.joinToString("/")
 }
 private fun parseXml(input:InputStream,onTag:(XmlPullParser)->Unit){
  input.use{
   val parser=Xml.newPullParser()
   parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true)
   parser.setInput(it,null)
   var events=0
   while(true){
    if(++events>200_000)throw IllegalArgumentException("EPUB XML이 너무 복잡합니다.")
    when(parser.next()){
     XmlPullParser.END_DOCUMENT->return
     XmlPullParser.START_TAG->onTag(parser)
    }
   }
  }
 }
 private fun readSpine(archive:ZipFile):List<String>{
  val container=archive.getEntry("META-INF/container.xml")?:throw IllegalArgumentException()
  if(container.size !in 1L..2_000_000L)throw IllegalArgumentException()
  var rootPath=""
  parseXml(archive.getInputStream(container)){p->if(p.name=="rootfile"&&rootPath.isEmpty())rootPath=p.getAttributeValue(null,"full-path").orEmpty()}
  if(rootPath.isEmpty())throw IllegalArgumentException()
  val opfPath=EpubPaths.resolve("",rootPath)?:throw IllegalArgumentException()
  val opf=archive.getEntry(opfPath)?:throw IllegalArgumentException()
  if(opf.size !in 1L..2_000_000L)throw IllegalArgumentException()
  val refs=HashMap<String,String>()
  val order=ArrayList<String>()
  parseXml(archive.getInputStream(opf)){p->
   when(p.name){
    "item"->{
     val id=p.getAttributeValue(null,"id").orEmpty()
     val href=p.getAttributeValue(null,"href").orEmpty()
     val media=p.getAttributeValue(null,"media-type").orEmpty()
     if(id.isNotBlank()&&href.isNotBlank()&&media in setOf("text/html","application/xhtml+xml")){
      val path=EpubPaths.resolve(opfPath,href)
      if(path!=null&&archive.getEntry(path)!=null)refs[id]=path
     }
    }
    "itemref"->p.getAttributeValue(null,"idref")?.let{order.add(it)}
   }
  }
  val chapters=order.mapNotNull{refs[it]}
  if(chapters.isEmpty())throw IllegalArgumentException()
  return chapters
 }
 private fun readToc(archive:ZipFile,chapters:List<String>):List<Pair<String,Int>>{
  val archiveEntries=archive.entries()
  var tocPath:String?=null
  var navPath:String?=null
  while(archiveEntries.hasMoreElements()){
   val path=archiveEntries.nextElement().name
   if(path.endsWith(".ncx",true))tocPath=path
   if(path.endsWith("nav.xhtml",true)||path.endsWith("toc.xhtml",true))navPath=path
  }
  fun chapterIndex(base:String,href:String):Int{
   val path=EpubPaths.resolve(base,href)?:return -1
   return chapters.indexOf(path)
  }
  data class TocItem(var title:String="",var href:String="")
  val entries=ArrayList<Pair<String,Int>>()
  if(tocPath!=null)runCatching{
   val parser=Xml.newPullParser().apply{setInput(archive.getInputStream(archive.getEntry(tocPath)),null)}
   val stack=ArrayList<TocItem>()
   while(true){when(parser.next()){
    XmlPullParser.END_DOCUMENT->break
    XmlPullParser.START_TAG->when(parser.name){
     "navPoint"->stack.add(TocItem())
     "content"->if(stack.isNotEmpty())stack.last().href=parser.getAttributeValue(null,"src").orEmpty()
     "text"->if(stack.isNotEmpty())stack.last().title=parser.nextText().trim()
    }
    XmlPullParser.END_TAG->if(parser.name=="navPoint"&&stack.isNotEmpty()){
     val item=stack.removeAt(stack.lastIndex);val index=chapterIndex(tocPath!!,item.href)
     if(index>=0&&item.title.isNotBlank())entries.add(item.title to index)
    }
   }}
  }
  if(entries.isEmpty()&&navPath!=null)runCatching{
   val nav=archive.getInputStream(archive.getEntry(navPath)).bufferedReader().use{it.readText()}
   val regex=Regex("(?is)<a\\b[^>]*href\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>(.*?)</a>")
   for(match in regex.findAll(nav)){
    val index=chapterIndex(navPath!!,match.groupValues[1])
    val title=Html.fromHtml(match.groupValues[2],Html.FROM_HTML_MODE_LEGACY).toString().trim()
    if(index>=0&&title.isNotBlank())entries.add(title to index)
   }
  }
  if(entries.isEmpty())return chapters.mapIndexed{index,path->
   val raw=path.substringAfterLast('/').substringBeforeLast('.').replace('_',' ').replace('-',' ')
   (raw.ifBlank{"${index+1}장"}) to index
  }
  return entries.distinct()
 }
 private fun styledXhtml(source:String):String{
  val active=ReaderFonts.family(this,fontChoice)?.takeIf{ReaderFonts.installed(this,it.id)}
  val selectedFont=active?.let{ReaderFonts.installedFile(this,it.id)}
  val fontCss=ReaderFontStyling.css(fontChoice,active?.id,
   selectedFont?.extension.equals("otf",true))
  val color=String.format("#%06X",fg() and 0xffffff)
  val background=String.format("#%06X",bg() and 0xffffff)
  val alignment=when(alignmentChoice){1->"left";2->"center";else->"justify"}
  val columns=if(twoPages())2 else 1
  val einkCss=if(GlobalConfig.isEInk(this))"*{animation:none !important;transition:none !important;text-shadow:none !important;box-shadow:none !important;}" else ""
  val boldCss=if(fontBold)"html body,html body *:not(svg):not(svg *){font-weight:700 !important;}" else ""
  val css="""<style type="text/css">html,body{background:$background !important;color:$color !important;margin:0 !important;}html{height:100% !important;overflow-x:auto !important;overflow-y:hidden !important;}body{box-sizing:border-box !important;width:100vw !important;height:100vh !important;overflow:visible !important;column-count:$columns !important;column-gap:${hMargin*2}px !important;column-fill:auto !important;line-height:${lineHeight/100f} !important;padding:${vMargin}px ${hMargin}px !important;}body>*{max-width:100% !important;}p{margin-bottom:${paragraph}px !important;text-indent:${indent}px !important;text-align:$alignment !important;}img,svg{max-width:100% !important;max-height:calc(100vh - ${vMargin*2}px) !important;object-fit:contain !important;}$fontCss$boldCss$einkCss</style>"""
  val match=Regex("(?i)</head\\s*>").find(source)
  return if(match!=null)source.replaceRange(match.range.first,match.range.first,css)
   else source.replace(Regex("(?i)<html([^>]*)>"),"<html$1><head>$css</head>")
 }
 private fun epubResource(archive:ZipFile,request:WebResourceRequest):WebResourceResponse{
  val uri=request.url
  if(uri.scheme!="https"||uri.host!="epub.local")return emptyResponse()
  if(uri.encodedPath?.startsWith("/__ckreader_fonts__/")==true) {
   val id=uri.lastPathSegment.orEmpty()
   val file=ReaderFonts.installedFile(this,id)?:return emptyResponse()
   return WebResourceResponse(ReaderFonts.mime(this,id),null,
    java.io.FileInputStream(file))
  }
  val path=try{normalPath(uri.path.orEmpty())}catch(_:Exception){""}
  val entry=archive.getEntry(path)
  if(entry==null||entry.isDirectory||entry.size>20L*1024L*1024L || path.endsWith(".js",true))return emptyResponse()
  val mime=when(path.substringAfterLast('.',"").lowercase()){
   "xhtml","html","htm"->"application/xhtml+xml"
   "css"->"text/css"
   "jpg","jpeg"->"image/jpeg"
   "png"->"image/png"
   "gif"->"image/gif"
   "svg"->"image/svg+xml"
   "ttf"->"font/ttf"
   "otf"->"font/otf"
   "woff"->"font/woff"
   "woff2"->"font/woff2"
   else->"application/octet-stream"
  }
  if(mime=="application/xhtml+xml"||mime=="text/html"){
   val source=archive.getInputStream(entry).bufferedReader().use{it.readText()}
   val safe=source.replace(Regex("(?is)<script\\b[^>]*>.*?</script>"),"")
    .replace(Regex("(?i)\\s+on[a-z]+\\s*=\\s*([\"'']).*?\\1"),"")
   return WebResourceResponse(mime,"UTF-8",styledXhtml(safe).byteInputStream())
  }
  return WebResourceResponse(mime,"UTF-8",archive.getInputStream(entry))
 }
 private fun spineUrl(index:Int):String="https://epub.local/"+spine[index].split('/').joinToString("/"){Uri.encode(it)}
 private fun pageIndexKey():String {
  val revision=ReaderFonts.family(this,fontChoice)?.let {
   ReaderFonts.revision(this)
  }?:0L
  return "pages_${docId}_${body.width}x${body.height}_${zoom}_${lineHeight}_${hMargin}_${vMargin}_${paragraph}_${indent}_${fontChoice}_${fontBold}_${revision}_${alignmentChoice}_${twoPages()}"
 }
 private fun startPageIndex(archive:ZipFile){
  indexEpoch++
  indexWeb?.let{body.removeView(it);it.destroy()}
  indexWeb=null
  val cacheKey=pageIndexKey()
  val cached=prefs.getString(cacheKey,null)?.split(',')?.mapNotNull{it.toIntOrNull()}
  if(cached!=null&&cached.size==total&&cached.all{it in 1..10000}){
   chapterPages=cached.toIntArray()
   updateProgress()
   return
  }
  chapterPages=IntArray(total)
  updateProgress()
  val epoch=indexEpoch
  var position=0
  val indexer=PageWebView(this).apply{
   settings.javaScriptEnabled=false
   settings.domStorageEnabled=false
   settings.allowFileAccess=false
   settings.allowContentAccess=false
   settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
   settings.cacheMode=WebSettings.LOAD_NO_CACHE
   settings.textZoom=zoom
   visibility=View.INVISIBLE
   isClickable=false
   importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
   webViewClient=object:WebViewClient(){
    override fun onPageFinished(v:WebView,url:String){
     v.postDelayed({
      if(finished||epoch!=indexEpoch)return@postDelayed
      val measured=(v as PageWebView).pageCount()
      chapterPages[position]=measured
      updateProgress()
      position++
      if(position<total)v.loadUrl(spineUrl(position))
      else{
       prefs.edit().putString(cacheKey,chapterPages.joinToString(",")).apply()
       body.removeView(v);v.destroy();indexWeb=null
       updateProgress()
      }
     },300)
    }
    override fun shouldInterceptRequest(v:WebView,request:WebResourceRequest)=epubResource(archive,request)
   }
  }
  indexWeb=indexer
  body.addView(indexer,0,FrameLayout.LayoutParams(-1,-1))
  indexer.loadUrl(spineUrl(position))
 }
 private fun openEpub(){
  worker.execute{
   try {
    val dir=File(cacheDir,"crema_reader").apply{mkdirs()}
    val file=File.createTempFile("crema-", ".epub", dir)
    tmp=file
    contentResolver.openInputStream(src!!)!!.use{input->
     file.outputStream().use{output->
      val buf=ByteArray(65536);var totalSize=0L
      while(true){
       val n=input.read(buf)
       if(n<0)break
       totalSize+=n
       if(totalSize>150L*1024L*1024L)throw IllegalArgumentException()
       output.write(buf,0,n)
      }
     }
    }
    val archive=ZipFile(file)
    zip=archive
    val chapters=readSpine(archive)
    val contents=readToc(archive,chapters)
    runOnUiThread {
     if(finished)return@runOnUiThread
     spine=chapters;toc=contents;total=spine.size
     page=prefs.getInt("index_"+docId,0).coerceIn(0,total-1)
     val view=PageWebView(this).apply {
      settings.javaScriptEnabled=true
      settings.domStorageEnabled=false
      settings.allowFileAccess=false
      settings.allowContentAccess=false
      settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
      settings.cacheMode=WebSettings.LOAD_NO_CACHE
      settings.textZoom=zoom
      setBackgroundColor(bg())
      var downX=0f;var downY=0f
      val hold=SentenceHoldGesture(this,mainHandler,{!finished&&ttsPlaying&&pageReady},{x,y->speakAtEpubTap(x,y)})
      setOnTouchListener{_,event->
       val held=hold.onTouch(event)
       when(event.actionMasked){
        MotionEvent.ACTION_DOWN->{downX=event.x;downY=event.y}
        MotionEvent.ACTION_UP->{
         val dx=event.x-downX;val dy=event.y-downY
         if(held)return@setOnTouchListener true
         if(gestureBrightness&&downX>width*.8f&&kotlin.math.abs(dy)>dp(60)&&kotlin.math.abs(dy)>kotlin.math.abs(dx)){
          brightness=(brightness-dy.toInt()/dp(8)).coerceIn(10,100);systemBrightness=false;saveSettings()
          window.attributes=window.attributes.apply{screenBrightness=brightness/100f}
         }
         if(kotlin.math.abs(dx)>dp(65)&&kotlin.math.abs(dx)>kotlin.math.abs(dy)){
          navigate(if(dx<0)1 else -1)
         }else if(touchPaging&&kotlin.math.abs(dx)<dp(15)&&kotlin.math.abs(dy)<dp(15)&&event.x<width*.18f)navigate(-1)
         else if(touchPaging&&kotlin.math.abs(dx)<dp(15)&&kotlin.math.abs(dy)<dp(15)&&event.x>width*.82f)navigate(1)
         else if(kotlin.math.abs(dx)<dp(15)&&kotlin.math.abs(dy)<dp(15)){
          val down=MotionEvent.obtain(event).apply{action=MotionEvent.ACTION_DOWN}
          onTouchEvent(down);down.recycle();onTouchEvent(event)
         }
        }
       };true
      }
      webViewClient=object:WebViewClient(){
       override fun onPageFinished(v:WebView,url:String){
        v.postDelayed({
         if(finished||url!=spineUrl(page))return@postDelayed
         val count=(v as PageWebView).pageCount()
         chapterPages[page]=count
         pageReady=true
         val restore=pendingSpreadIndex
         if(restore!=null){
          pendingSpreadIndex=null
          spreadIndex=restore.coerceIn(0,count-1)
         }else if(pendingLastSpread){
          pendingLastSpread=false;spreadIndex=count-1
         }else spreadIndex=spreadIndex.coerceIn(0,count-1)
         v.scrollTo(spreadIndex*v.width,0)
         savePage()
         updateProgress()
         val start=pendingEpubSpeechStart;pendingEpubSpeechStart=null
         start?.invoke()
         if(ttsPlaying&&!speechFollowing)reconnectSpeechHighlightIfVisible()
         else highlightSpeechPart()
        },400)
       }
       override fun shouldOverrideUrlLoading(v:WebView,request:WebResourceRequest):Boolean =
        request.url.scheme!="https"||request.url.host!="epub.local"
       override fun shouldInterceptRequest(v:WebView,request:WebResourceRequest)=epubResource(archive,request)
      }
     }
     web=view;body.removeAllViews();body.addView(view,FrameLayout.LayoutParams(-1,-1))
     showPage(page)
     body.post{if(!finished)startPageIndex(archive)}
    }
   }catch(e:Exception){android.util.Log.e("CremaReader","EPUB open failed",e);tmp?.delete();fail("EPUB 파일을 열 수 없습니다.\nDRM이 적용된 파일이거나 EPUB 구조가 지원되지 않습니다.")}
  }
 }
 private fun emptyResponse()=WebResourceResponse("text/plain","UTF-8",ByteArray(0).inputStream())
 private fun changeZoom(step:Int){
  if(format !in setOf("epub","txt","md"))return
  zoom=(zoom+step).coerceIn(70,200)
  web?.settings?.textZoom=zoom
  if(format in setOf("txt","md"))renderText()
  saveSettings()
 }
 override fun onNewIntent(newIntent:Intent) {
  super.onNewIntent(newIntent)
  if(newIntent.getBooleanExtra(ReaderTtsNotificationService.EXTRA_JUMP_TO_SPEECH,false))
   intent.putExtra(ReaderTtsNotificationService.EXTRA_JUMP_TO_SPEECH,true)
 }

 override fun onResume() {
  super.onResume()
  if(!::body.isInitialized)return
  prefs.getString(settingsKey("font_id"),null)?.let{
   fontChoice=ReaderFonts.index(this,it).coerceAtLeast(0)
  }
  // Restore the active narration page after a screen lock, including PDF.
  // Keep the original session and TTS engine running while the Activity is paused.
  val revision=ReaderFonts.family(this,fontChoice)?.let {
   ReaderFonts.revision(this)
  }?:0L
  if(appliedFontRevision>=0 && revision!=appliedFontRevision)applyWindowSettings()
  appliedFontRevision=revision
  if(intent.getBooleanExtra(ReaderTtsNotificationService.EXTRA_JUMP_TO_SPEECH,false)){
   intent.removeExtra(ReaderTtsNotificationService.EXTRA_JUMP_TO_SPEECH)
   body.post{if(!finished)returnToSpeechPosition()}
  }
  if(ttsPlaying && speechPreparedPage in 0 until total) {
   speechFollowing=true
   if(page!=speechPreparedPage)showPage(speechPreparedPage,fromSpeech=true)
   else if(format=="epub")highlightSpeechPart()
   if(format=="epub")body.post { if(!finished)highlightSpeechPart() }
   updateProgress()
  }
 }
 private fun setComicDirection(direction:ComicReadingPolicy.Direction){
  if(!isComic()||comicDirection==direction)return
  comicDirection=direction
  saveSettings()
  if(total>0){
   page=ComicReadingPolicy.first(page,total,comicSpread())
   setupImagePage()
   showPage(page)
  }
 }
 private fun setComicTwoPage(enabled:Boolean){
  comicTwoPage=enabled
  saveSettings()
  if(isComic()&&total>0&&comicDirection==ComicReadingPolicy.Direction.HORIZONTAL){
   page=ComicReadingPolicy.first(page,total,comicSpread())
   setupImagePage()
   showPage(page)
  }
 }

 private fun showComicFullscreenAction(){
  if(!isComic()||finished||isFinishing||isDestroyed)return
  comicFullscreenPopup?.dismiss()
  // Only a single action button is visible. A focusable PopupWindow with
  // a transparent background dismisses automatically on outside touches.
  val button=button(ComicCenterTapPolicy.actionLabel(comicFullscreen)){}
  button.textSize=15f
  styleButton(button,primary=true)
  button.minWidth=dp(156)
  button.setPadding(dp(20),dp(6),dp(20),dp(6))
  val popup=PopupWindow(button,
   ViewGroup.LayoutParams.WRAP_CONTENT,
   ViewGroup.LayoutParams.WRAP_CONTENT,true).apply {
   setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
   isOutsideTouchable=true
   elevation=dp(10).toFloat()
  }
  button.setOnClickListener {
   popup.dismiss()
   if(!finished&&!isFinishing)setComicFullscreen(!comicFullscreen)
  }
  popup.setOnDismissListener {
   if(comicFullscreenPopup===popup)comicFullscreenPopup=null
  }
  comicFullscreenPopup=popup
  popup.showAtLocation(window.decorView,Gravity.CENTER,0,0)
 }

 private fun setComicFullscreen(enabled:Boolean){
  comicFullscreenPopup?.dismiss()
  if(!isComic()||comicFullscreen==enabled)return
  comicFullscreen=enabled
  header.visibility=if(enabled)View.GONE else View.VISIBLE
  menu.visibility=if(enabled)View.GONE else View.VISIBLE
  val controller=androidx.core.view.WindowCompat.getInsetsController(window,window.decorView)
  if(enabled){
   controller.systemBarsBehavior=
    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
   controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
  }else{
   controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
  }
  androidx.core.view.ViewCompat.requestApplyInsets(column)
  body.post {
   if(finished)return@post
   if(comicDirection==ComicReadingPolicy.Direction.VERTICAL)
    comicVerticalList?.relayoutAt(page)
   else renderComic()
  }
 }

 override fun onBackPressed(){
  if(isComic()&&comicFullscreen)setComicFullscreen(false)
  else super.onBackPressed()
 }

 private fun settingsKey(name:String)= (if(prefs.getBoolean("all_books",false))"all_" else "book_${docId}_")+name
 private fun loadSettings(){
  zoom=prefs.getInt(settingsKey("zoom"),110)
  lineHeight=prefs.getInt(settingsKey("line"),160)
  hMargin=prefs.getInt(settingsKey("hmargin"),12)
  vMargin=prefs.getInt(settingsKey("vmargin"),8)
  paragraph=prefs.getInt(settingsKey("paragraph"),8)
  indent=prefs.getInt(settingsKey("indent"),0)
  val savedFontId=prefs.getString(settingsKey("font_id"),null)
  fontChoice=if(savedFontId!=null)ReaderFonts.index(this,savedFontId).coerceAtLeast(0)
   else prefs.getInt(settingsKey("font"),0)
  fontBold=prefs.getBoolean(settingsKey("font_bold"),false)
  alignmentChoice=prefs.getInt(settingsKey("alignment"),0)
  backgroundChoice=prefs.getInt(settingsKey("background"),0).coerceIn(0,ReaderBackgrounds.tones.lastIndex)
  brightness=prefs.getInt(settingsKey("brightness"),100)
  systemBrightness=prefs.getBoolean(settingsKey("system_brightness"),true)
  showPageInfo=prefs.getBoolean(settingsKey("page_info"),true)
  touchPaging=prefs.getBoolean(settingsKey("touch_paging"),true)
  volumePaging=prefs.getBoolean(settingsKey("volume_paging"),false)
  rotationLock=prefs.getBoolean(settingsKey("rotation_lock"),false)
  keepScreenOn=prefs.getBoolean(settingsKey("keep_screen_on"),true)
  autoScroll=if(GlobalConfig.isEInk(this))false else prefs.getBoolean(settingsKey("auto_scroll"),false)
  pageTurnMs=prefs.getInt(settingsKey("page_turn_ms"),600).coerceIn(500,2000)
  gestureBrightness=if(GlobalConfig.isEInk(this))false else prefs.getBoolean(settingsKey("gesture_brightness"),false)
  dualPageWide=prefs.getBoolean(settingsKey("dual_page_wide"),true)
  comicDirection=if(prefs.getString(settingsKey("comic_direction"),"horizontal")=="vertical")
   ComicReadingPolicy.Direction.VERTICAL else ComicReadingPolicy.Direction.HORIZONTAL
  comicTwoPage=prefs.getBoolean(settingsKey("comic_two_page"),false)
  ttsRate=prefs.getFloat("tts_rate",1.0f)
  ttsPitch=prefs.getFloat("tts_pitch",1.0f)
  ttsVoiceIndex=prefs.getInt("tts_voice",0)
  ttsEngine=prefs.getString("tts_engine","").orEmpty()
  ttsVoiceName=prefs.getString("tts_voice_name","").orEmpty()
  ttsMode=prefs.getString("tts_mode","ai").orEmpty()
  if(ttsEngine=="com.samsung.SMT"){
   ttsEngine="";ttsMode="ai";ttsVoiceName="";ttsVoiceIndex=0
   prefs.edit().remove("tts_engine").remove("tts_voice_name").putString("tts_mode","ai").putInt("tts_voice",0).apply()
  }
  aiVoiceIndex=prefs.getInt("ai_voice",0).coerceIn(0,AiVoiceData.names.lastIndex)
  ridiVoiceIndex=prefs.getInt("ridi_voice",0).coerceIn(0,RidiVoiceData.names.lastIndex)
  willaVoiceIndex=prefs.getInt("willa_voice",0).coerceIn(0,WillaVoices.names.lastIndex)
  if(ttsMode=="ai"&&!VoiceModules.installed(this,"yes24") ||
   ttsMode=="ridi"&&!VoiceModules.installed(this,"ridi") ||
   ttsMode=="willa"&&!VoiceModules.installed(this,"willa")){
   ttsMode=when {
    VoiceModules.installed(this,"yes24")->"ai"
    VoiceModules.installed(this,"ridi")->"ridi"
    VoiceModules.installed(this,"willa")->"willa"
    else->"android"
   }
  }
  ttsAutoLanguage=prefs.getBoolean("tts_auto_language",false)
  ttsReadParentheses=prefs.getBoolean("tts_read_parentheses",true)
  ttsSkipHanja=prefs.getBoolean("tts_skip_hanja",false)
 }
 private fun saveSettings(){
  prefs.edit().putInt(settingsKey("zoom"),zoom).putInt(settingsKey("line"),lineHeight)
   .putInt(settingsKey("hmargin"),hMargin).putInt(settingsKey("vmargin"),vMargin)
   .putInt(settingsKey("paragraph"),paragraph).putInt(settingsKey("indent"),indent)
   .putInt(settingsKey("font"),fontChoice)
   .putString(settingsKey("font_id"),ReaderFonts.family(this,fontChoice)?.id)
   .putBoolean(settingsKey("font_bold"),fontBold)
   .putInt(settingsKey("alignment"),alignmentChoice).putInt(settingsKey("background"),backgroundChoice)
   .putInt(settingsKey("brightness"),brightness).putBoolean(settingsKey("system_brightness"),systemBrightness)
   .putBoolean(settingsKey("page_info"),showPageInfo).putBoolean(settingsKey("touch_paging"),touchPaging)
   .putBoolean(settingsKey("volume_paging"),volumePaging).putBoolean(settingsKey("rotation_lock"),rotationLock)
   .putBoolean(settingsKey("keep_screen_on"),keepScreenOn)
   .putBoolean(settingsKey("auto_scroll"),autoScroll).putBoolean(settingsKey("gesture_brightness"),gestureBrightness)
   .putInt(settingsKey("page_turn_ms"),pageTurnMs)
   .remove(settingsKey("magnifier")).putBoolean(settingsKey("dual_page_wide"),dualPageWide)
   .putString(settingsKey("comic_direction"),
    if(comicDirection==ComicReadingPolicy.Direction.VERTICAL)"vertical" else "horizontal")
   .putBoolean(settingsKey("comic_two_page"),comicTwoPage).apply()
 }
 private fun applyWindowSettings(){
  window.statusBarColor=design().bg();window.navigationBarColor=design().bg()
  window.decorView.systemUiVisibility=if(dark())0 else
   View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
  window.attributes=window.attributes.apply{screenBrightness=if(GlobalConfig.isEInk(this@StandaloneReaderActivity)||systemBrightness)-1f else brightness/100f}
  // A visible reader never enters the display timeout. The power button still
  // locks the screen normally; KEEP_SCREEN_ON does not hold a CPU wake lock.
  window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  requestedOrientation=if(rotationLock)ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
  progress.visibility=if(showPageInfo)View.VISIBLE else View.INVISIBLE
  if(::column.isInitialized){
   column.setBackgroundColor(chromeColor())
   header.setBackgroundColor(chromeColor())
   menu.setBackgroundColor(chromeColor())
   fun repaint(view:View){
    if(view is WebView||view===body)return
    if(view is Button)styleButton(view,view.text?.toString()=="TTS")
    else if(view is TextView)view.setTextColor(
     if(view===progress)design().secondary() else chromeText())
    if(view is ViewGroup)for(i in 0 until view.childCount)repaint(view.getChildAt(i))
   }
   repaint(column)
  }
  body.setBackgroundColor(bg())
  image?.setBackgroundColor(bg())
  web?.setBackgroundColor(bg())
  web?.settings?.textZoom=zoom
  if(format=="pdf"&&twoPages())page-=page%2
  if(isComic()&&comicFullscreen)
   androidx.core.view.WindowCompat.getInsetsController(window,window.decorView)
    .hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
  lastSpreadMode=twoPages()
  if(format=="epub")web?.let{
   pendingSpreadIndex=spreadIndex;pageReady=false;it.reload()
   zip?.let{archive->body.post{if(!finished)startPageIndex(archive)}}
  } else if(total>0)when(format){
   "pdf"->renderPdf()
   "txt","md"->renderText()
   "zip","cbz"->renderComic()
  }
  mainHandler.removeCallbacks(scrollTick)
  if(autoScroll&&format=="epub")mainHandler.postDelayed(scrollTick,90)
 }
 private fun settingRow(label:String,initial:Boolean,onChange:(Boolean)->Unit):Switch = Switch(this).apply{
  text=label;textSize=15f;isChecked=initial;setPadding(dp(12),dp(6),dp(12),dp(6))
  setOnCheckedChangeListener{_,value->onChange(value);saveSettings();applyWindowSettings()}
 }
 private fun seekRow(label:String,minValue:Int,maxValue:Int,current:Int,onChange:(Int)->Unit):LinearLayout {
  val row=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(7),dp(12),dp(7))}
  val caption=TextView(this).apply{text="$label  $current";textSize=15f;setTextColor(fg())}
  val seek=SeekBar(this).apply{max=maxValue-minValue;progress=current-minValue}
  seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(bar:SeekBar?,value:Int,fromUser:Boolean){if(fromUser){val n=minValue+value;caption.text="$label  $n";onChange(n)}}
   override fun onStartTrackingTouch(bar:SeekBar?){}
   override fun onStopTrackingTouch(bar:SeekBar?){saveSettings();applyWindowSettings()}
  })
  row.addView(caption);row.addView(seek);return row
 }
 private fun speechSeekRow(label:String,minTenths:Int,maxTenths:Int,currentTenths:Int,onChange:(Float)->Unit):LinearLayout{
  val row=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(7),dp(12),dp(7))}
  val caption=TextView(this).apply{text="$label  ${currentTenths/10f}×";textSize=15f;setTextColor(fg())}
  val seek=SeekBar(this).apply{max=maxTenths-minTenths;progress=currentTenths-minTenths}
  seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(bar:SeekBar?,value:Int,fromUser:Boolean){if(fromUser){val selected=(minTenths+value)/10f;caption.text="$label  $selected×";onChange(selected)}}
   override fun onStartTrackingTouch(bar:SeekBar?){}
   override fun onStopTrackingTouch(bar:SeekBar?){}
  })
  row.addView(caption);row.addView(seek);return row
 }
 private fun settingsPanel(title:String, build:(LinearLayout)->Unit){
  val scroll=ScrollView(this)
  val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(4),dp(10),dp(4))}
  build(list);ReaderDesign.finishButtons(list);scroll.addView(list)
  AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("닫기",null).show()
 }
 private fun showBackgroundPicker() {
  val colors=ReaderBackgrounds.tones
  val grid=android.widget.GridLayout(this).apply {
   columnCount=3
   setPadding(dp(10),dp(12),dp(10),dp(14))
  }
  val dialog=AlertDialog.Builder(this).setTitle("독서 배경 색상")
   .setView(ScrollView(this).apply {addView(grid)})
   .setNegativeButton("닫기",null).create()
  for((index,tone) in colors.withIndex()) {
   val active=index==backgroundChoice
   val card=LinearLayout(this).apply {
    orientation=LinearLayout.VERTICAL
    gravity=Gravity.CENTER
    setPadding(dp(5),dp(7),dp(5),dp(7))
    isClickable=true
    isFocusable=true
    contentDescription=tone.label+(if(active)" · 선택됨" else "")
   }
   val bgColor=if(index==0)design().bg()
    else Color.parseColor(tone.bg)
   val swatch=View(this).apply {
    background=ReaderDesign.shape(this@StandaloneReaderActivity,
     design(),bgColor,if(active)design().brand() else design().outline(),11f)
   }
   card.addView(swatch,LinearLayout.LayoutParams(dp(82),dp(56)))
   val caption=TextView(this).apply {
    text=tone.label
    textSize=12f
    gravity=Gravity.CENTER
    setTextColor(chromeText())
    typeface=Typeface.create("sans-serif-medium",if(active)Typeface.BOLD else Typeface.NORMAL)
   }
   card.addView(caption,LinearLayout.LayoutParams(dp(88),dp(27)))
   card.setOnClickListener {
    backgroundChoice=index
    saveSettings()
    applyWindowSettings()
    dialog.dismiss()
   }
   grid.addView(card,android.widget.GridLayout.LayoutParams().apply {
    width=dp(94);height=dp(98)
    setMargins(dp(1),dp(2),dp(1),dp(2))
   })
  }
  dialog.show()
 }

 private fun openFontManager() {
  runCatching {
   startActivity(Intent().setClassName(packageName,"com.ckchoi.ckreader.LibraryActivity")
    .putExtra("ckreader_open_fonts",true))
  }.onFailure{
   Toast.makeText(this,"앱 설정에서 글꼴 소개를 확인할 수 있습니다.",Toast.LENGTH_LONG).show()
  }
 }
 private fun showStyleSettings(){
  settingsPanel("보기설정") { list ->
   list.addView(settingRow("모든 책에 적용",prefs.getBoolean("all_books",false)){value->
    prefs.edit().putBoolean("all_books",value).apply();saveSettings()
   })
   if(format in setOf("epub","txt","md")){
    list.addView(seekRow("글자 크기",70,200,zoom){zoom=it})
    val fontChoices=listOf(0 to "원본글꼴",1 to "명조",2 to "고딕")+
     ReaderFonts.allFamilies(this).mapIndexedNotNull{index,family->
      if(ReaderFonts.installed(this,family.id))index+3 to family.label else null
     }
    val fonts=fontChoices.map{it.second}.toTypedArray()
    val fontButton=button("글꼴선택"){}
    fontButton.setOnClickListener {
     val adapter=object:android.widget.ArrayAdapter<String>(this,
      android.R.layout.simple_list_item_single_choice,fonts) {
      override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
       val row=super.getView(position,convertView,parent) as TextView
       ReaderFonts.family(this@StandaloneReaderActivity,fontChoices[position].first)?.let{family->
        row.typeface=ReaderFonts.font(this@StandaloneReaderActivity,family.id)
         ?:Typeface.DEFAULT
       }?:run{row.typeface=if(fontChoices[position].first==1)Typeface.SERIF else Typeface.DEFAULT}
       return row
      }
     }
     AlertDialog.Builder(this).setTitle("읽기 글꼴 선택")
      .setSingleChoiceItems(adapter,fontChoices.indexOfFirst{it.first==fontChoice}){dialog,which->
       val choice=fontChoices[which].first
       val family=ReaderFonts.family(this,choice)
       if(family!=null && ReaderFonts.font(this,family.id)==null) {
        Toast.makeText(this,"글꼴팩의 글꼴을 읽지 못했습니다. 글꼴팩 APK를 확인해주세요.",Toast.LENGTH_LONG).show()
       } else {
        fontChoice=choice
        saveSettings()
        applyWindowSettings()
        dialog.dismiss()
       }
      }.setNeutralButton("글꼴 소개"){_,_->openFontManager()}.show()
    }
    list.addView(fontButton)
    list.addView(settingRow("글꼴 굵게",fontBold){fontBold=it})
    list.addView(seekRow("줄 간격",100,240,lineHeight){lineHeight=it})
    list.addView(seekRow("좌우 여백",0,48,hMargin){hMargin=it})
    list.addView(seekRow("상하 여백",0,48,vMargin){vMargin=it})
    list.addView(seekRow("문단 간격",0,32,paragraph){paragraph=it})
    list.addView(seekRow("들여쓰기",0,48,indent){indent=it})
    val alignments=arrayOf("양쪽 정렬","왼쪽 정렬","가운데 정렬")
    list.addView(button("문단 정렬 · ${alignments[alignmentChoice.coerceIn(0,2)]}"){
     val choices=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10))}
     val dialog=AlertDialog.Builder(this).setTitle("문단 정렬")
      .setView(choices).setNegativeButton("취소",null).create()
     alignments.forEachIndexed{which,name->
      val row=LinearLayout(this).apply{
       orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
       setPadding(dp(14),dp(8),dp(14),dp(8))
       background=GradientDrawable().apply{
        setColor(if(which==alignmentChoice)0x223b82f6 else Color.TRANSPARENT)
        cornerRadius=dp(10).toFloat()
       }
       addView(ImageView(this@StandaloneReaderActivity).apply{
        setImageBitmap(alignmentIcon(which))
        contentDescription="$name 모양"
       },LinearLayout.LayoutParams(dp(58),dp(42)))
       addView(TextView(this@StandaloneReaderActivity).apply{
        text=name;textSize=16f;setTextColor(chromeText())
        setPadding(dp(14),0,0,0)
       })
       setOnClickListener{alignmentChoice=which;saveSettings();applyWindowSettings();dialog.dismiss()}
      }
      choices.addView(row,LinearLayout.LayoutParams(-1,dp(64)))
     }
     dialog.show()
    })
   }
   if(GlobalConfig.isEInk(this)) list.addView(TextView(this).apply {
    text="AINOTE 2 · 흰색 배경과 검은색 글자 사용";textSize=15f
    setTextColor(chromeText());setPadding(dp(12),dp(7),dp(12),dp(7))
   })
   else {
    list.addView(button("배경 색 · ${ReaderBackgrounds.get(backgroundChoice).label}"){
     showBackgroundPicker()
    })
    list.addView(settingRow("시스템 밝기",systemBrightness){systemBrightness=it})
    list.addView(seekRow("밝기",10,100,brightness){brightness=it;systemBrightness=false})
   }
   list.addView(button("설정 초기화"){
    zoom=110;lineHeight=160;hMargin=12;vMargin=8;paragraph=8;indent=0;fontChoice=0;fontBold=false;alignmentChoice=0;backgroundChoice=0;brightness=100;systemBrightness=true
    saveSettings();applyWindowSettings();Toast.makeText(this,"보기 설정을 초기화했습니다.",Toast.LENGTH_SHORT).show()
   })
  }
 }
 private fun alignmentIcon(choice:Int):Bitmap {
  val bitmap=Bitmap.createBitmap(dp(58),dp(42),Bitmap.Config.ARGB_8888)
  val canvas=Canvas(bitmap)
  val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=chromeText()}
  val widths=intArrayOf(42,35,39,27)
  widths.forEachIndexed{line,width->
   val actual=if(choice==0&&line<3)42 else width
   val x=when(choice){2->(58-actual)/2;else->7}
   val top=4+line*10
   canvas.drawRoundRect(dp(x).toFloat(),dp(top).toFloat(),
    dp(x+actual).toFloat(),dp(top+4).toFloat(),dp(2).toFloat(),dp(2).toFloat(),paint)
  }
  return bitmap
 }
 private fun showScreenSettings(){
  settingsPanel("화면설정") { list ->
   if(isComic()){
    val modeButton=button("읽기 방향"){}
    val spreadSwitch=settingRow("폴드 펼친 화면에서 2페이지 보기",comicTwoPage) {
     setComicTwoPage(it)
    }
    val description=TextView(this).apply{
     textSize=12f
     setTextColor(design().secondary())
     setPadding(dp(12),dp(6),dp(12),dp(10))
    }
    fun refreshComicControls(){
     val vertical=comicDirection==ComicReadingPolicy.Direction.VERTICAL
     modeButton.text="ZIP/CBZ 읽기 방향 · "+if(vertical)"세로스크롤" else "가로스크롤"
     spreadSwitch.visibility=if(vertical)View.GONE else View.VISIBLE
     description.text=if(vertical)
      "현재 페이지부터 최대 3장의 이미지를 붙여 표시하고, 스크롤할 때 다음 페이지를 한 장씩 불러옵니다."
     else "2페이지 보기는 폴드를 펼친 큰 화면에서만 적용됩니다. 좌우로 넘길 때 화면 단위로 이동합니다."
    }
    modeButton.setOnClickListener{
     val labels=arrayOf("가로스크롤 · 페이지 넘기기","세로스크롤 · 웹툰처럼 이어보기 (최대 3장 로딩)")
     val selected=if(comicDirection==ComicReadingPolicy.Direction.VERTICAL)1 else 0
     AlertDialog.Builder(this).setTitle("ZIP/CBZ 이미지 읽기 방향")
      .setSingleChoiceItems(labels,selected){dialog,choice->
       setComicDirection(if(choice==1)ComicReadingPolicy.Direction.VERTICAL
        else ComicReadingPolicy.Direction.HORIZONTAL)
       refreshComicControls()
       dialog.dismiss()
      }.setNegativeButton("취소",null).show()
    }
    refreshComicControls()
    list.addView(modeButton)
    list.addView(spreadSwitch)
    list.addView(description)
   }else list.addView(settingRow("펼친 화면에서 양면보기",dualPageWide){dualPageWide=it;updateProgress()})
   if(!GlobalConfig.isEInk(this))
    list.addView(seekRow("책장 넘김 시간 (짧을수록 빠름)",500,2000,pageTurnMs){pageTurnMs=it})
   list.addView(settingRow("상단 페이지 정보 표시",showPageInfo){showPageInfo=it})
   list.addView(settingRow("페이지 터치 영역 · 좌우터치",touchPaging){touchPaging=it})
   if(format=="epub"){
    list.addView(settingRow("자동 페이지 넘기기",autoScroll){autoScroll=it})
   }
   list.addView(settingRow("볼륨키로 페이지 넘기기",volumePaging){volumePaging=it})
   if(!GlobalConfig.isEInk(this))list.addView(settingRow("상하 제스처로 밝기 조절",gestureBrightness){gestureBrightness=it})
   list.addView(settingRow("화면 회전 잠금",rotationLock){rotationLock=it})
   list.addView(settingRow("도서 읽기 중 화면 켜짐",true){keepScreenOn=true})
  }
 }
 private fun showContents(){
  if(total==0){Toast.makeText(this,"문서를 불러오는 중입니다.",Toast.LENGTH_SHORT).show();return}
  if((total>2000&&format!="epub")||streamedComic()){
   val picker=android.widget.NumberPicker(this).apply {
    minValue=1;maxValue=total;value=page+1;wrapSelectorWheel=false
   }
   AlertDialog.Builder(this).setTitle("페이지 이동").setView(picker)
    .setNegativeButton("취소",null).setPositiveButton("이동"){_,_->showPage(picker.value-1)}.show()
   return
  }
  val entries=when(format){
   "pdf"->(0 until total).map{"${it+1}쪽" to it}
   "txt"->(0 until total).map{"${it+1}페이지" to it}
   "zip","cbz"->comicPages.mapIndexed{index,name ->name.replace('\\','/').substringAfterLast('/') to index}
   else->toc
  }
  if(entries.isEmpty()){Toast.makeText(this,"목차가 없습니다.",Toast.LENGTH_SHORT).show();return}
  val indexed=format!="epub" || chapterPages.size==total&&chapterPages.all{it>0}
  val labels=entries.map{(name,index)->
   if(format!="epub")"${index+1} / $total  ·  $name"
   else if(indexed)"$name  ·  ${chapterPages.take(index).sum()+1}쪽"
   else "$name  ·  페이지 계산 중"
  }.toTypedArray()
  AlertDialog.Builder(this).setTitle("목차").setItems(labels){_,which->showPage(entries[which].second)}.setNegativeButton("닫기",null).show()
 }
 private fun koreanVoices()=tts?.voices?.filter{it.locale.language=="ko"}?.sortedWith(compareBy<android.speech.tts.Voice>{it.isNetworkConnectionRequired}.thenBy{it.name}).orEmpty()
 private fun applyTtsOptions(){
  tts?.setSpeechRate(ttsRate);tts?.setPitch(ttsPitch)
  val voices=koreanVoices()
  if(voices.isNotEmpty()){
   val selected=voices.indexOfFirst{it.name==ttsVoiceName}.takeIf{it>=0}?:ttsVoiceIndex.coerceIn(0,voices.lastIndex)
   tts?.voice=voices[selected]
  }
  else tts?.language=Locale.KOREAN
 }
 private fun ensureTts(after:()->Unit){
  if(ttsReady){after();return}
  if(tts!=null){Toast.makeText(this,"음성 엔진을 준비하는 중입니다.",Toast.LENGTH_SHORT).show();return}
  tts=TextToSpeech(this,{status->runOnUiThread{
   if(status==TextToSpeech.SUCCESS){ttsReady=true;applyTtsOptions();tts?.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
    override fun onStart(id:String?){}
    override fun onRangeStart(id:String?,start:Int,end:Int,frame: Int){
     mainHandler.post{
      if(ttsPlaying&&id=="$speechSequence:$speechPart")highlightSpeechWord(start,end)
     }
    }
    override fun onDone(id:String?){mainHandler.post{if(ttsPlaying&&id=="$speechSequence:$speechPart"){
     advanceSpeech()
    }}}
    override fun onError(id:String?){mainHandler.post{stopSpeaking();Toast.makeText(this@StandaloneReaderActivity,"음성을 재생할 수 없습니다.",Toast.LENGTH_SHORT).show()}}
   });after()}
   else {tts?.shutdown();tts=null;Toast.makeText(this,"기기의 음성 엔진을 사용할 수 없습니다.",Toast.LENGTH_LONG).show()}
  }},ttsEngine.ifBlank{null})
 }
 private fun advanceSpeech(){
  if(!ttsPlaying)return
  speechPart++
  if(speechPart<speechParts.size){speakPart();return}
  val next=speechPreparedPage+speechPreparedStep
  if(next<total){if(speechFollowing)showPage(next,fromSpeech=true);startSpeaking(fromBeginning=true,pageOverride=next)}
  else stopSpeaking()
 }
 private fun clearSpeechHighlight(){
  speechHighlightEpoch++
  web?.evaluateJavascript(EpubSpeechHighlight.clear(),null)
 }
 private fun highlightSpeechPart(){
  val view=web?:return
  if(!pageReady||!ttsPlaying||!speechFollowing||page!=speechPreparedPage)return
  val part=speechChunks.getOrNull(speechPart)?:return
  val epoch=++speechHighlightEpoch
  val preparedPage=speechPreparedPage
  view.evaluateJavascript(EpubSpeechHighlight.script(part.start,part.end,spineUrl(preparedPage))){result->
   if(finished||!ttsPlaying||!speechFollowing||epoch!=speechHighlightEpoch||web!==view||
    page!=preparedPage||!pageReady||view.width<=0)return@evaluateJavascript
   val data=runCatching{org.json.JSONObject(result)}.getOrNull()?:return@evaluateJavascript
   val next=data.optInt("spread",spreadIndex).coerceIn(0,(chapterPages.getOrNull(page)?:1)-1)
   // Find-in-page centers matches at arbitrary offsets. Reader navigation always uses whole spreads.
   view.scrollTo(next*view.width,0)
   if(next!=spreadIndex){spreadIndex=next;savePage();updateProgress()}
  }
 }
 private fun highlightSpeechWord(start:Int,end:Int){
  if(format!="epub"||!speechFollowing||!pageReady||page!=speechPreparedPage)return
  val part=speechChunks.getOrNull(speechPart)?:return
  val view=web?:return
  val from=(part.start+start).coerceIn(part.start,part.end)
  val to=(part.start+end).coerceIn(from,part.end)
  if(to<=from)return
  val chapter=page
  view.evaluateJavascript(EpubSpeechHighlight.word(from,to,spineUrl(chapter))){result->
   if(finished||!ttsPlaying||!speechFollowing||page!=chapter||web!==view)return@evaluateJavascript
   val next=runCatching{org.json.JSONObject(result).optInt("spread",-1)}.getOrDefault(-1)
   if(next in 0 until (chapterPages.getOrNull(page)?:1)&&next!=spreadIndex) {
    view.scrollTo(next*view.width,0)
    spreadIndex=next;savePage();updateProgress()
   }
  }
 }
 /** A manual swipe detaches autoplay scrolling, not the narration itself.
  * Reconnect the highlight only when the visible chapter and spread contain
  * the *current* spoken phrase; never force the user away from other pages.
  */
 private fun reconnectSpeechHighlightIfVisible(){
  if(!ttsPlaying||speechFollowing||format!="epub"||!pageReady||
   page!=speechPreparedPage)return
  val view=web?:return
  val phrase=speechChunks.getOrNull(speechPart)?:return
  val chapter=page
  val shownSpread=spreadIndex
  val phraseIndex=speechPart
  val sequence=speechSequence
  val epoch=speechHighlightEpoch
  view.evaluateJavascript(EpubSpeechHighlight.locate(phrase.start,phrase.end,spineUrl(chapter))){result->
   if(finished||web!==view||!pageReady||speechFollowing||
    speechSequence!=sequence||speechPart!=phraseIndex||speechHighlightEpoch!=epoch||
    page!=chapter||spreadIndex!=shownSpread)return@evaluateJavascript
   val spokenSpread=runCatching{org.json.JSONObject(result).optInt("spread",-1)}.getOrDefault(-1)
   if(EpubSpeechNavigation.shouldReconnect(ttsPlaying,chapter,speechPreparedPage,
    shownSpread,spokenSpread)){
    speechFollowing=true
    highlightSpeechPart()
   }
  }
 }

 /** A long press on the header page counter returns to the speech position. */
 private fun returnToSpeechPosition(){
  if((!ttsPlaying&&!ttsPaused)||speechPreparedPage !in 0 until total)return
  speechFollowing=true
  if(page!=speechPreparedPage)showPage(speechPreparedPage,fromSpeech=true)
  else if(format=="epub"){
   if(pageReady)highlightSpeechPart()
   // The EPUB page-finished callback highlights after a pending load.
  } else if(format=="pdf")showPage(speechPreparedPage,fromSpeech=true)
  updateProgress()
 }

 private fun ensureSpeechEngine(after:()->Unit){
  if(ttsMode=="android"){ensureTts(after);return}
  if(aiVoice==null)aiVoice=AiVoiceEngine(this,{advanceSpeech()},{message->stopSpeaking();Toast.makeText(this,message,Toast.LENGTH_LONG).show()})
  aiVoice?.prepare(when(ttsMode){"ridi"->"ridi";"willa"->"willa";else->"yes24"}){if(it)after()}
 }
 private fun updateListeningAction(){
  if(::progress.isInitialized&&total>0)updateProgress()
  listeningAction?.text=if(ttsPlaying)"일시정지" else "재생"
  if(::playbackButton.isInitialized){
   playbackButton.text=if(ttsPlaying)"정지" else "재생"
   playbackButton.contentDescription=if(ttsPlaying)"TTS 듣기 정지" else "마지막으로 선택한 음성으로 재생"
  }
 }
 private fun stopSpeaking(){
  speechStartEpoch++;pendingEpubSpeechStart=null
  lastChapterNavigation=System.currentTimeMillis()
  ttsPlaying=false;ttsPaused=false
  updateSpeechWakeLock();updateListeningAction();clearSpeechHighlight()
  speechSequence++;speechPreparedPage=-1
  tts?.stop();aiVoice?.stop()
  notifyTts()
 }
 private fun pauseSpeaking(){
  speechStartEpoch++;pendingEpubSpeechStart=null
  ttsPaused=ttsPlaying;ttsPlaying=false
  updateSpeechWakeLock();updateListeningAction();clearSpeechHighlight()
  speechSequence++;tts?.stop();aiVoice?.stop()
  notifyTts()
 }
 private fun resumeSpeaking(){
  if(ttsPlaying)return
  if(ttsPaused&&speechPreparedPage in 0 until total&&
   speechPart in speechParts.indices) {
   ttsPaused=false;ttsPlaying=true;speechFollowing=true
   speechSequence++
   updateSpeechWakeLock();updateListeningAction()
   returnToSpeechPosition()
   speakPart()
  } else {speechEmptyPages=0;startSpeaking()}
 }
 private fun speakPart(){
  if(!ttsPlaying||speechPart !in speechParts.indices)return
  if(timerDeadline>0&&System.currentTimeMillis()>=timerDeadline){stopSpeaking();return}
  // Never send an EPUB scene divider such as "***" to the ONNX voice.
  // Advance asynchronously to avoid a deep callback chain on many separators.
  if(ttsMode=="willa"&&!speechParts[speechPart].any{it in '가'..'힣'||it in '0'..'9'}){
   val sequence=speechSequence
   mainHandler.post {if(ttsPlaying&&sequence==speechSequence)advanceSpeech()}
   return
  }
  highlightSpeechPart()
  notifyTts()
  if(ttsMode!="android")aiVoice?.play(speechParts[speechPart],when(ttsMode){"ridi"->ridiVoiceIndex;"willa"->willaVoiceIndex;else->aiVoiceIndex},ttsRate,ttsPitch,ttsReadParentheses,ttsSkipHanja)
  else {
   if(speechEnglish.getOrNull(speechPart)==true)tts?.language=Locale.US else applyTtsOptions()
   tts?.speak(speechParts[speechPart],TextToSpeech.QUEUE_FLUSH,null,"$speechSequence:$speechPart")
  }
 }
 private fun speakAtEpubTap(x:Float,y:Float) {
  val view=web?:return
  val px=x/view.width.coerceAtLeast(1).toFloat()
  val py=y/view.height.coerceAtLeast(1).toFloat()
  val heldPage=page;val heldSpread=spreadIndex;val epoch=speechStartEpoch
  view.evaluateJavascript(EpubSpeechSnapshot.script(px,py)){result->
   val position=parseSpeechStart(result)
   if(!finished&&ttsPlaying&&position!=null&&page==heldPage&&spreadIndex==heldSpread&&epoch==speechStartEpoch){
    stopSpeaking()
    requestedEpubSpeech=position.copy(offset=SpeechPosition.sentenceStart(position.text,position.offset))
    startSpeaking()
   }
  }
 }
 private fun parseSpeechStart(result:String):SpeechStart?=runCatching {
  val data=org.json.JSONObject(result)
  SpeechStart(data.getString("text"),data.getInt("offset"))
 }.getOrNull()
 private fun visibleTextStart():SpeechStart? {
  val text=txtView?:return null
  val layout=text.layout?:return null
  val visible=Rect();if(!text.getLocalVisibleRect(visible))return null
  val line=layout.getLineForVertical((visible.top-text.totalPaddingTop).coerceAtLeast(0))
  return SpeechStart(text.text.toString(),layout.getLineStart(line))
 }
 private fun visiblePdfStart():PdfSpeechRegion? {
  val visible=(image as? ZoomablePageView)?.visibleImageRect()?:return null
  for(region in pdfSpeechRegions) {
   if(region.page !in page..(page+if(twoPages())1 else 0))continue
   val intersection=RectF(region.bounds)
   if(!intersection.intersect(visible))continue
   val b=region.bounds
   return PdfSpeechRegion(region.page,RectF((intersection.left-b.left)/b.width(),(intersection.top-b.top)/b.height(),
    (intersection.right-b.left)/b.width(),(intersection.bottom-b.top)/b.height()))
  }
  return null
 }
 private fun startSpeaking(fromBeginning:Boolean=false,pageOverride:Int?=null){
  if(format !in setOf("epub","pdf","txt","md"))return
  if(android.os.Build.VERSION.SDK_INT>=33&&
    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=
     android.content.pm.PackageManager.PERMISSION_GRANTED &&
    !prefs.getBoolean("tts_notification_permission_asked",false)) {
   prefs.edit().putBoolean("tts_notification_permission_asked",true).apply()
   requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),313)
  }
  val requestedPage=pageOverride?:page
  if(format=="pdf"&&!fromBeginning&&pdfRenderedPage!=requestedPage){
   Toast.makeText(this,"페이지를 준비한 뒤 재생해주세요.",Toast.LENGTH_SHORT).show();return
  }
  val explicit=requestedSpeechOffset;requestedSpeechOffset=null
  val epubOverride=requestedEpubSpeech;requestedEpubSpeech=null
  val pdfOverride=requestedPdfSpeech;requestedPdfSpeech=null
  val pdfVisible=if(format=="pdf"&&!fromBeginning&&pdfOverride==null)visiblePdfStart() else null
  stopSpeaking()
  val epoch=speechStartEpoch
  if(!fromBeginning)speechFollowing=true
  fun begin(position:SpeechStart?) {
   if(finished||epoch!=speechStartEpoch)return
  ensureSpeechEngine {
   if(finished||epoch!=speechStartEpoch||!fromBeginning&&page!=requestedPage)return@ensureSpeechEngine
   val request=++speechSequence
   ttsPlaying=true
   ttsPaused=false
   updateSpeechWakeLock()
   updateListeningAction()
   listeningAction?.text=if(format=="pdf")"텍스트 추출 / OCR 중..." else "본문 준비 중..."
   val spread=format=="pdf"&&twoPages()
   worker.execute {
    val spoken=try { if(position!=null)position.text else
     when(format){
      "epub"->{
       val chapter=spine.getOrNull(requestedPage)?:error("EPUB 본문을 찾을 수 없습니다.")
       val entry=zip?.getEntry(chapter)?:error("EPUB 본문을 찾을 수 없습니다.")
       require(entry.size in 0L..2_000_000L){"EPUB 본문이 너무 큽니다."}
       val html=zip!!.getInputStream(entry).bufferedReader().use{it.readText()}
       Html.fromHtml(html.replace(Regex("""(?is)<(script|style)[^>]*>.*?</\1>"""),""),
        Html.FROM_HTML_MODE_LEGACY).toString()
      }
      "txt"->txtPages.getOrNull(requestedPage).orEmpty()
      "md"->markdownSpanned(txtPages.getOrNull(requestedPage).orEmpty()).toString()
      "pdf"->{
       if(pdfOverride!=null)pdfOverride else {
       val renderer=pdf?:error("PDF 뷰어를 열지 못했습니다.")
       val extractor=pdfSpeech ?: PdfSpeechExtractor(applicationContext,src!!).also{pdfSpeech=it}
       buildString {
        val selected=pdfVisible?.page?:requestedPage
        val bounds=pdfVisible?.bounds
        append(if(bounds!=null&&(bounds.top>.001f||bounds.left>.001f||bounds.bottom<.999f||bounds.right<.999f))
         extractor.extractVisible(selected,renderer,bounds) else extractor.extract(selected,renderer))
        if(spread&&selected==requestedPage&&requestedPage+1<total){
         append("\n\n")
         append(extractor.extract(requestedPage+1,renderer))
        }
       }
       }
      }
      else->""
     }
    }catch(e:Exception){
     android.util.Log.w("CremaReader","Speech extraction failed",e)
     ""
    }
    val prepared=SpeechText.prepare(spoken,position?.offset?:0,ttsReadParentheses,ttsSkipHanja,ttsAutoLanguage)
    mainHandler.post {
     if(finished||!ttsPlaying||request!=speechSequence)return@post
     speechChunks=prepared
     speechParts=prepared.map{it.text}
     speechEnglish=prepared.map{it.english}
     speechPart=0
     speechPreparedPage=requestedPage
     speechPreparedStep=if(spread)2 else 1
     if(speechParts.isEmpty()){
      val step=if(format=="pdf"&&spread)2 else 1
      if(format=="pdf"&&speechEmptyPages<4&&requestedPage+step<total){
       speechEmptyPages++
       if(speechFollowing)showPage(requestedPage+step,fromSpeech=true)
       startSpeaking(fromBeginning=true,pageOverride=requestedPage+step)
      }else{
       stopSpeaking()
       val explanation=if(format=="pdf")
        "이 페이지에서 읽을 문자를 찾지 못했습니다. 스캔 문서는 OCR 결과에 따라 듣기가 제한될 수 있습니다."
       else "읽을 본문이 없습니다."
       Toast.makeText(this,explanation,Toast.LENGTH_LONG).show()
      }
     }else{
      speechEmptyPages=0
      updateListeningAction()
      speakPart()
     }
    }
   }
  }
  }
  when {
   format=="epub"&&epubOverride!=null->begin(epubOverride)
   format=="epub"&&(!fromBeginning||speechFollowing&&page==requestedPage)->{
    val view=web
    if(view==null){return}
    fun capture(){
     if(finished||page!=requestedPage||epoch!=speechStartEpoch)return
     val spread=spreadIndex
     view.evaluateJavascript(EpubSpeechSnapshot.script()){result->
      if(page!=requestedPage||spreadIndex!=spread||epoch!=speechStartEpoch)return@evaluateJavascript
      val position=parseSpeechStart(result)
      if(position==null)Toast.makeText(this,"현재 화면에서 읽을 본문을 찾지 못했습니다.",Toast.LENGTH_SHORT).show()
      else begin(if(fromBeginning)position.copy(offset=0) else position)
     }
    }
    // The next chapter must have the same laid-out DOM as its speech offsets before playback begins.
    if(pageReady)capture()
    else if(fromBeginning)pendingEpubSpeechStart=::capture
    else Toast.makeText(this,"페이지를 준비한 뒤 재생해주세요.",Toast.LENGTH_SHORT).show()
   }
   fromBeginning->begin(null)
   format in setOf("txt","md")->visibleTextStart()?.let{begin(if(explicit!=null)it.copy(offset=explicit) else it)}
   else->begin(null)
  }
 }
 private fun showListening(){
  if(format !in setOf("epub","pdf","txt","md"))return
  settingsPanel("듣기") { list ->
   listeningAction=button(if(ttsPlaying)"일시정지" else "재생"){
    if(ttsPlaying)pauseSpeaking() else resumeSpeaking()
   }
   list.addView(listeningAction)
   list.addView(speechSeekRow("읽기속도",5,30,(ttsRate*10).toInt()){
    ttsRate=it;prefs.edit().putFloat("tts_rate",ttsRate).apply();applyTtsOptions()
   })
   list.addView(button("음성 엔진"){
    ensureTts {
     val engines=tts?.engines.orEmpty().filter{it.name!="com.samsung.SMT"}
     val available=buildList {
      if(VoiceModules.installed(this@StandaloneReaderActivity,"yes24"))add("ai" to "Selvy I AI 음성")
      if(VoiceModules.installed(this@StandaloneReaderActivity,"ridi"))add("ridi" to "Selvy II AI 음성")
      if(VoiceModules.installed(this@StandaloneReaderActivity,"willa"))add("willa" to "ONNX I AI 음성")
     }
     val labels=available.map{it.second}+engines.map{it.label}
     val selected=if(ttsMode=="android")available.size+engines.indexOfFirst{it.name==ttsEngine}.coerceAtLeast(0)
      else available.indexOfFirst{it.first==ttsMode}.coerceAtLeast(0)
     AlertDialog.Builder(this).setTitle("음성 엔진").setSingleChoiceItems(labels.toTypedArray(),selected){dialog,which->
      stopSpeaking()
      if(which<available.size){
       val next=available[which].first
       if(ttsMode!=next){aiVoice?.close();aiVoice=null}
       ttsMode=next;prefs.edit().putString("tts_mode",next).apply()
      }else {aiVoice?.close();aiVoice=null;ttsMode="android";ttsEngine=engines[which-available.size].name;ttsVoiceName="";ttsVoiceIndex=0
       prefs.edit().putString("tts_mode","android").putString("tts_engine",ttsEngine).remove("tts_voice_name").putInt("tts_voice",0).apply()
       tts?.shutdown();tts=null;ttsReady=false
      }
      dialog.dismiss()
      if(which>=available.size)ensureTts { Toast.makeText(this,"${labels[which]} 엔진을 선택했습니다.",Toast.LENGTH_SHORT).show() }
     }.show()
    }
   })
   list.addView(button("음성 선택"){
    if(ttsMode=="ai"||ttsMode=="ridi"||ttsMode=="willa"){
     val ridi=ttsMode=="ridi"
     val willa=ttsMode=="willa"
     AlertDialog.Builder(this).setTitle(if(ridi)"Selvy II AI 음성 선택" else if(willa)"ONNX I AI 음성 선택" else "Selvy I AI 음성 선택")
      .setSingleChoiceItems(if(ridi)RidiVoiceData.names else if(willa)WillaVoices.names else AiVoiceData.names,
       if(ridi)ridiVoiceIndex else if(willa)willaVoiceIndex else aiVoiceIndex){dialog,which->
       stopSpeaking()
       if(ridi){ridiVoiceIndex=which;prefs.edit().putInt("ridi_voice",which).apply()}
       else if(willa){willaVoiceIndex=which;prefs.edit().putInt("willa_voice",which).apply()}
       else {aiVoiceIndex=which;prefs.edit().putInt("ai_voice",which).apply()}
       dialog.dismiss()
      }.setPositiveButton("확인",null).show()
     return@button
    }
    ensureTts {
     val voices=koreanVoices()
     if(voices.isEmpty())Toast.makeText(this,"사용 가능한 한국어 음성이 없습니다.",Toast.LENGTH_SHORT).show()
     else AlertDialog.Builder(this).setTitle("음성 선택").setSingleChoiceItems(voices.map{voice->
      val group=voices.filter{it.isNetworkConnectionRequired==voice.isNetworkConnectionRequired}
      (if(voice.isNetworkConnectionRequired)"온라인 한국어 음성 " else "기기 한국어 음성 ")+(group.indexOf(voice)+1)
     }.toTypedArray(),voices.indexOfFirst{it.name==tts?.voice?.name}){dialog,which->
      ttsVoiceIndex=which;ttsVoiceName=voices[which].name;prefs.edit().putInt("tts_voice",which).putString("tts_voice_name",ttsVoiceName).apply();applyTtsOptions();dialog.dismiss()
     }.show()
    }
   })
   list.addView(button("타이머"){
    val labels=arrayOf("사용 안 함","10분","20분","30분","60분")
    val minutes=intArrayOf(0,10,20,30,60)
    AlertDialog.Builder(this).setTitle("타이머").setItems(labels){_,which->timerDeadline=if(minutes[which]==0)0 else System.currentTimeMillis()+minutes[which]*60_000L}.show()
   })
   list.addView(speechSeekRow("음정",5,20,(ttsPitch*10).toInt()){
    ttsPitch=it;prefs.edit().putFloat("tts_pitch",ttsPitch).apply();applyTtsOptions()
   })
   list.addView(button("듣기설정"){
    settingsPanel("듣기설정"){options->
     options.addView(settingRow("한영 자동 전환",ttsAutoLanguage){ttsAutoLanguage=it;prefs.edit().putBoolean("tts_auto_language",it).apply()})
     options.addView(settingRow("괄호안 문장읽기",ttsReadParentheses){ttsReadParentheses=it;prefs.edit().putBoolean("tts_read_parentheses",it).apply()})
     options.addView(settingRow("한자 읽지 않음",ttsSkipHanja){ttsSkipHanja=it;prefs.edit().putBoolean("tts_skip_hanja",it).apply()})
    }
   })
  }
  if(!ttsPlaying){speechEmptyPages=0;startSpeaking()}
 }
 override fun onKeyDown(keyCode:Int,event:android.view.KeyEvent):Boolean {
  if(volumePaging&&keyCode==android.view.KeyEvent.KEYCODE_VOLUME_DOWN){navigate(1);return true}
  if(volumePaging&&keyCode==android.view.KeyEvent.KEYCODE_VOLUME_UP){navigate(-1);return true}
  return super.onKeyDown(keyCode,event)
 }
 override fun onConfigurationChanged(newConfig:Configuration){
  val wasSpread=lastSpreadMode
  super.onConfigurationChanged(newConfig)
  if(isComic()){
   lastSpreadMode=twoPages()
   lastComicSpreadMode=comicSpread()
   if(total>0)body.post{
    if(!finished){
     page=ComicReadingPolicy.first(page,total,comicSpread())
     setupImagePage()
     showPage(page)
    }
   }
   return
  }
  lastSpreadMode=twoPages()
  if(wasSpread!=twoPages()){
   if(format=="pdf"){
    if(twoPages())page-=page%2
    body.post{updateProgress();renderPdf()}
   }else if(format=="epub"){
    intent.putExtra("restoreSpread",if(wasSpread)spreadIndex*2 else spreadIndex/2)
    recreate()
   }else if(format in setOf("zip","cbz")&&total>0)body.post{renderComic()}
   else if(format in setOf("txt","md")&&total>0)body.post{renderText()}
  }else if(format=="pdf"&&total>0)body.post{renderPdf()}
  else if(format in setOf("zip","cbz")&&total>0)body.post{renderComic()}
 }
 override fun onDestroy(){
  if(::body.isInitialized)clearPageTurn()
  comicFullscreenPopup?.dismiss()
  comicFullscreenPopup=null
  if(ttsReceiverRegistered){
   runCatching{unregisterReceiver(ttsNotificationReceiver)}
   ttsReceiverRegistered=false
  }
  com.ckchoi.elibrary.common.ReaderDocumentSession.close(this)
  finished=true;generation++
  mainHandler.removeCallbacks(scrollTick)
  stopSpeaking()
  tts?.shutdown();tts=null
  aiVoice?.close();aiVoice=null
  indexEpoch++
  indexWeb?.destroy();indexWeb=null
  web?.destroy();web=null
  // Keep active PDF/OCR and ZIP reads serialized with resource cleanup.
  // Destroying the activity must not close the renderer while OCR is reading a page.
  comicVerticalList?.dispose();comicVerticalList=null
  finishComicLoading()
  comicRangeChannel?.close();comicRangeChannel=null
  worker.execute {
   runCatching{pdfSpeech?.close()};pdfSpeech=null
   runCatching{pdf?.close()};pdf=null
   runCatching{pdfFd?.close()};pdfFd=null
   runCatching{zip?.close()};zip=null
   runCatching{comicSeekZip?.close()};comicSeekZip=null
   runCatching{comicSeekFd?.close()};comicSeekFd=null
   tmp?.delete();tmp=null
   if(intent.getBooleanExtra("deleteOnClose",false)&&!isChangingConfigurations)src?.path?.let {path->
    runCatching {
     val file=File(path).canonicalFile
     val root=File(cacheDir,"yes24-reader").canonicalFile
     if(file.parentFile==root)file.delete()
    }
   }
  }
  worker.shutdown()
  pagePrefetch.shutdownNow()
  image?.setImageBitmap(null)
  comicBitmap?.recycle()
  pdfBitmap?.recycle()
  pdfBitmap=null
  comicBitmap=null
  super.onDestroy()
 }
}
