package com.ckchoi.ckreader

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.accounts.AccountManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.view.WindowManager
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ckchoi.elibrary.common.GlobalConfig
import com.ckchoi.elibrary.common.ReaderDesign
import com.ckchoi.elibrary.common.ReaderFonts
import com.ckchoi.yesss24.StandaloneReaderActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.json.JSONObject
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity

class LibraryActivity : Activity() {
 private enum class Page { LIBRARY, SETTINGS, FONTS, GOOGLE_OAUTH_GUIDE, ADD_SOURCE, FORM, MANAGE, DRIVE_REMOTES,
  SEARCH, BOOK_DETAIL, CACHE_REBUILD }
 private sealed class Entry {
  data class Heading(val value:String) : Entry()
  data class Storage(val source:LibrarySource) : Entry()
  data class Recent(val value:RecentBook) : Entry()
  data class Folder(val name:String) : Entry()
  data class Book(val value:LibraryBook,val number:Int) : Entry()
  data class Notice(val message:String) : Entry()
  data object AddStorage : Entry()
  data object LoadMore : Entry()
 }
 private class Holder(val panel:LinearLayout) : RecyclerView.ViewHolder(panel)

 private val worker=Executors.newSingleThreadExecutor()
 private val operations=Executors.newSingleThreadExecutor()
 // Large downloads must not block catalog operations or the UI thread.
 private val bookOpenWorker=Executors.newCachedThreadPool()
 private val bookOpenHandler=Handler(Looper.getMainLooper())
 private var bookOpenTask:Future<*>?=null
 private var bookOpenDialog:AlertDialog?=null
 private var bookOpenTicker:Runnable?=null
 private var bookOpenSequence=0

 private val coverLoader by lazy {CatalogCoverLoader()}
 private val coverRefreshWorker=Executors.newSingleThreadExecutor()
 private val detailWorker=Executors.newSingleThreadExecutor()
 private val cacheUsageWorker=Executors.newSingleThreadExecutor()
 private val exitGuard=DoubleBackExitGuard()
 private val storageIcons by lazy {SourceIconStore(applicationContext)}
 private data class StorageIconBinding(val sourceId:String,val type:String)
 private var detailTask:Future<*>?=null
 private var detailLoadVersion=0
 private var detailLoading=false
 private val refreshingCovers=hashSetOf<String>()
 private var scanTask:Future<*>?=null
 @Volatile private var scanVersion=0
 private var autoLoadingBookOasis=false
 private val seriesInfoBackfills=HashSet<String>()
 private lateinit var repository:LibraryRepository
 private lateinit var adapter:CatalogAdapter
 private lateinit var status:TextView
 private var page=Page.LIBRARY
 private var selectedSourceId:String?=null
 private var currentFolder=""
 private var bookOasisSearch=""
 private var lastListing:LibraryListing?=null
 private var editing:LibrarySource?=null
 private var formType=""
 private var formPreset=""
 private var addSourceBack=Page.LIBRARY
 private var formReturnPage=Page.ADD_SOURCE
 private var editingLocalId:String?=null
 private var driveRemotes:Map<String,JSONObject> = emptyMap()
 private var pendingDriveConfig=""
 private var fontsRevision=0L
 private val localRequest=101
 private val driveRequest=102
 private val googleAuthorizationRequest=112
 private val googleAccountRequest=113
 private var googleAuthEditingSourceId:String?=null
 private var googleAuthSelectedEmail:String?=null
 private var driveFolderPicker:Dialog?=null
 private val cacheTreeRequest=104
 private val storageIconRequest=105
 private val fontFileRequest=106
 private val moduleUninstallRequest=114
 private var pendingModuleUninstall:Pair<String,String>?=null
 private var pendingIconSourceId:String?=null
 private val navigation by lazy { getSharedPreferences("ckreader_navigation",MODE_PRIVATE) }
 private var metadataListening=false
 private val scrollAnchors=HashMap<String,Triple<String,Int,Int>>()
 // Returning via Back is different from opening a folder with a click.
 // A new destination always starts at item 0 even when visited previously.
 private var requestedBackAnchor:String?=null
 private var catalogView:RecyclerView?=null
 private var detailBook:LibraryBook?=null
 private var detailSeriesPath=""
 private var searchText=""
 private var searchSourceId=""
 private var searchDepth=""
 private var searchResults:List<LibraryBook> = emptyList()
 private var searchBusy=false
 private val rebuildSelections=LinkedHashSet<CacheRebuildService.Selection>()
 private val rebuildDepths=LinkedHashMap<String,List<Pair<String,String>>>()
 private val rebuildBusy=HashSet<String>()
 private val rebuildUsage=HashMap<String,CacheUsageSnapshot>()
 private val rebuildUsageFailed=HashSet<String>()
 private data class CacheSizeLabel(val source:LibrarySource,val depth:String,val title:String,val view:TextView)
 private val rebuildSizeLabels=ArrayList<CacheSizeLabel>()
 private var cacheUsageBusy=false
 private var cacheUsageRequested=false
 private val notifyPermissionRequest=109
 private var waitingSelections:List<CacheRebuildService.Selection> = emptyList()
 private var waitingMode=CacheRebuildService.StartMode.WAIT
 private var cacheRebuildForce=false
 private var waitingForceRebuild=false
 private val expandedRebuildSources=HashSet<String>()
 private val cacheProgressStore by lazy {CacheRebuildProgressStore(applicationContext)}
 private val cacheProgressHandler=Handler(Looper.getMainLooper())
 private val cacheProgressPoll=object:Runnable {
  override fun run() {
   if(!isDestroyed && page in setOf(Page.SETTINGS,Page.CACHE_REBUILD))
    refreshCacheProgress()
   if(!isDestroyed)cacheProgressHandler.postDelayed(this,1000)
  }
 }
 private var cacheProgressSummary:TextView?=null
 private var cacheProgressCurrent:TextView?=null
 private var cacheProgressCancel:Button?=null
 private var cacheProgressHistory:LinearLayout?=null
 private var cacheProgressHistoryMarker:Int?=null
 private var showCacheProgressHistory=true
 private val cacheReceiver=object:BroadcastReceiver() {
  override fun onReceive(context:Context,intent:Intent) {
   if(!CacheRebuildService.running)
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
   if(isDestroyed)return
   refreshCacheProgress()
   coverLoader.clearMemory()
   if(page==Page.CACHE_REBUILD){cacheUsageRequested=false;render()}
   else if(page==Page.LIBRARY&&selectedSource()!=null) {
    val source=selectedSource()?:return
    repository.invalidateListingMemory(source.id)
    val updated=repository.cached(source,currentFolder,
     if(source.type=="bookoasis")bookOasisSearch else "")
    if(updated!=null&&::adapter.isInitialized)showListing(source,updated,true,false)
    else if(::adapter.isInitialized)adapter.notifyDataSetChanged()
   }
  }
 }
 private fun navigationKey()=selectedSourceId.orEmpty()+"|"+currentFolder+"|"+bookOasisSearch

 private fun currentVisibleAnchor():Triple<String,Int,Int>? {
  if(page!=Page.LIBRARY)return null
  val recycler=catalogView?.takeIf{it.isAttachedToWindow}?:return null
  val manager=recycler.layoutManager as? LinearLayoutManager?:return null
  val position=manager.findFirstVisibleItemPosition().takeIf{it>=0}?:return null
  val row=when(val item=adapter.entryAt(position)){
   is Entry.Book->"book:"+item.value.id
   is Entry.Folder->"folder:"+item.name
   else->return null
  }
  return Triple(row,position,manager.findViewByPosition(position)?.top?:0)
 }
 private fun saveLibraryAnchor(){
  currentVisibleAnchor()?.let{scrollAnchors[navigationKey()]=it}
 }
 private fun restoreAnchor(anchor:Triple<String,Int,Int>?,rows:List<Entry>){
  val state=anchor?:return
  val list=catalogView?:return
  val keys=rows.map {item->
   when(item){
    is Entry.Book->"book:"+item.value.id
    is Entry.Folder->"folder:"+item.name
    is Entry.Heading->"heading:"+item.value
    else->null
   }
  }
  val position=LibraryScrollPolicy.position(keys,state.first,state.second)
  (list.layoutManager as? LinearLayoutManager)
   ?.scrollToPositionWithOffset(position,state.third)
 }

 private val metadataReceiver=object:BroadcastReceiver() {
  override fun onReceive(context:Context,intent:Intent) {
   if(!::repository.isInitialized)return
   intent.getStringExtra("folderCoverPath")?.let{path->
    val sourceId=intent.getStringExtra("source")?:return
    repository.invalidateListingMemory(sourceId)
    coverLoader.invalidate(CatalogCoverKey.folder(sourceId,path))
    if(page==Page.LIBRARY&&sourceId==selectedSourceId&&path.isNotEmpty()&&
       path.substringBeforeLast('/',"")==currentFolder&&::adapter.isInitialized) {
     val position=adapter.positionOfFolder(path.substringAfterLast('/'))
     if(position>=0)adapter.notifyItemChanged(position)
    }
    return
   }
   if(intent.getBooleanExtra("yamlUpdated",false)) {
    intent.getStringExtra("source")?.let{repository.invalidateListingMemory(it)}
    coverLoader.clearMemory()
   }
   if(page!=Page.LIBRARY)return
   val source=selectedSource()?:return
   if(intent.getStringExtra("source")!=source.id||
     intent.getStringExtra("path")!=currentFolder||
     intent.getStringExtra("search").orEmpty()!=(if(source.type=="bookoasis")bookOasisSearch else ""))return
   val updated=repository.cached(source,currentFolder,if(source.type=="bookoasis")bookOasisSearch else "")
   if(updated!=null)showListing(source,updated,true,false)
  }
 }

 private fun dp(n:Int)=(n*resources.displayMetrics.density+0.5f).toInt()
 private fun dark()=GlobalConfig.isDark(this)
 private fun palette()=ReaderDesign.tokens(this)
 private fun ink()=palette().fg()
 private fun paper()=palette().bg()
 private fun surface()=palette().card()
 private fun raised()=palette().raisedColor()
 private fun muted()=palette().secondary()
 private fun line()=palette().outline()
 private fun accent()=palette().brand()
 private fun secondaryTint()=palette().tint()
 private fun danger()=palette().errorColor()
 private fun shape(fill:Int=surface(),outline:Int=line(),radius:Float=15f)=
  ReaderDesign.shape(this,palette(),fill,outline,radius)
 private fun ripple(fill:Int=surface(),outline:Int=line(),radius:Float=15f):RippleDrawable {
  val responsive=android.graphics.drawable.StateListDrawable().apply {
   addState(intArrayOf(android.R.attr.state_focused),shape(fill,accent(),radius))
   addState(intArrayOf(android.R.attr.state_pressed),shape(fill,accent(),radius))
   addState(intArrayOf(),shape(fill,outline,radius))
  }
  return RippleDrawable(
   ColorStateList.valueOf(if(GlobalConfig.isEInk(this))Color.TRANSPARENT else if(dark())Color.argb(52,175,213,255) else Color.argb(34,36,92,255)),
   responsive,shape(Color.WHITE,Color.WHITE,radius))
 }
 private fun label(value:String,size:Float=14f,meta:Boolean=false)=TextView(this).apply {
  text=value
  textSize=size
  setTextColor(if(meta)muted() else ink())
  typeface=when {
   meta->Typeface.create("sans-serif-medium",Typeface.NORMAL)
   size>=22f->Typeface.create("sans-serif",Typeface.BOLD)
   size>=16f->Typeface.create("sans-serif-medium",Typeface.NORMAL)
   else->Typeface.create("sans-serif",Typeface.NORMAL)
  }
  setPadding(dp(3),dp(4),dp(3),dp(4))
  includeFontPadding=true
  if(meta)letterSpacing=0.015f
 }
 private fun icon(id:Int,size:Int=24,tint:Int=ink())=ImageView(this).apply {
  setImageResource(id);imageTintList=ColorStateList.valueOf(tint)
  layoutParams=LinearLayout.LayoutParams(dp(size),dp(size))
  scaleType=ImageView.ScaleType.FIT_CENTER
 }
 private fun iconButton(id:Int,description:String,onClick:()->Unit)=ImageButton(this).apply {
  setImageResource(id)
  imageTintList=ColorStateList.valueOf(accent())
  contentDescription=description
  background=ripple(surface(),line(),13f)
  ReaderDesign.flatButton(this)
  scaleType=ImageView.ScaleType.CENTER_INSIDE
  setPadding(dp(12),dp(12),dp(12),dp(12))
  layoutParams=LinearLayout.LayoutParams(dp(48),dp(48))
  importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
  setOnClickListener { onClick() }
 }
 private fun action(text:String,primary:Boolean=false,onClick:()->Unit)=Button(this).apply {
  this.text=text;isAllCaps=false;textSize=13f
  typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
  minHeight=dp(48);minimumHeight=dp(48)
  setTextColor(if(primary)palette().brandText() else palette().tintText())
  background=ripple(if(primary)accent() else secondaryTint(),
   if(primary)accent() else line(),16f)
  ReaderDesign.flatButton(this)
  setPadding(dp(14),0,dp(14),0)
  setOnClickListener { onClick() }
 }
 private fun cardLayoutParams()=LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(8)}
 private fun horizontal()=LinearLayout(this).apply {
  orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
  // Apply gaps after addView(..., explicit LayoutParams) has assigned widths.
  setOnHierarchyChangeListener(object:ViewGroup.OnHierarchyChangeListener {
   override fun onChildViewAdded(parent:View?,child:View?) {
    if(child !is Button && child !is ImageButton)return
    val params=child.layoutParams as? LinearLayout.LayoutParams ?:return
    params.marginStart=dp(4);params.marginEnd=dp(4)
    child.layoutParams=params
   }
   override fun onChildViewRemoved(parent:View?,child:View?){}
  })
 }
 private fun vertical()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
 private fun divider(strong:Boolean=false)=View(this).apply {
  setBackgroundColor(if(strong)line() else Color.TRANSPARENT)
  layoutParams=LinearLayout.LayoutParams(-1,dp(if(strong)1 else 8))
 }
 private fun section(value:String)=vertical().apply {
  setPadding(dp(3),dp(21),dp(3),dp(8))
  addView(label(value,12f,true).apply {
   setTextColor(accent())
   typeface=Typeface.create("sans-serif",Typeface.BOLD)
   letterSpacing=.06f
  })
 }
 private fun selectedSource()=repository.sources().firstOrNull { it.id==selectedSourceId }
 private fun sourceIcon(type:String)=when(type) {
  "local"->R.drawable.ic_storage_local
  "webdav"->R.drawable.ic_storage_webdav
  "ftp"->R.drawable.ic_storage_ftp
  "sftp"->R.drawable.storage_icon_sftp
  "drive"->R.drawable.ic_storage_drive
  "bookoasis"->R.drawable.ic_storage_bookoasis
  else->R.drawable.ic_storage_local
 }
 private fun sourceType(type:String)=when(type) {
  "local"->"로컬 폴더"
  "webdav"->"WebDAV"
  "ftp"->"FTP"
  "sftp"->"SFTP"
  "drive"->"Google Drive"
  "bookoasis"->"북오아시스"
  else->type
 }
 private fun bindStorageIcon(view:ImageView,source:LibrarySource) {
  view.tag=StorageIconBinding(source.id,source.type)
  view.scaleType=ImageView.ScaleType.FIT_CENTER
  val choice=storageIcons.selected(source.id,source.type)
  val custom=choice!=null&&storageIcons.image(view,choice)
  if(custom) {
   view.imageTintList=null
   view.background=shape(Color.WHITE,line(),12f)
   view.setPadding(dp(5),dp(5),dp(5),dp(5))
  }else {
   view.setImageResource(sourceIcon(source.type))
   view.imageTintList=ColorStateList.valueOf(accent())
   view.background=shape(secondaryTint(),line(),12f)
   view.setPadding(dp(10),dp(10),dp(10),dp(10))
  }
 }
 private fun refreshStorageIcons() {
  val sources=repository.sources().associateBy{it.id}
  fun visit(view:View) {
   val binding=view.tag as? StorageIconBinding
   if(view is ImageView&&binding!=null)sources[binding.sourceId]?.let{bindStorageIcon(view,it)}
   if(view is ViewGroup)for(index in 0 until view.childCount)visit(view.getChildAt(index))
  }
  visit(window.decorView)
 }
 private fun showStorageIconPicker(source:LibrarySource) {
  exitGuard.reset()
  val content=vertical().apply{setPadding(dp(16),dp(6),dp(16),0)}
  val search=EditText(this).apply {
   hint="아이콘 검색";setSingleLine(true);textSize=14f
   setTextColor(ink());setHintTextColor(muted())
  }
  content.addView(search,LinearLayout.LayoutParams(-1,dp(48)))
  val scroll=ScrollView(this).apply{isFillViewport=false}
  val grid=android.widget.GridLayout(this).apply{columnCount=3}
  scroll.addView(grid,ViewGroup.LayoutParams(-1,-2))
  content.addView(scroll,LinearLayout.LayoutParams(-1,
   (resources.displayMetrics.heightPixels*.52f).toInt().coerceAtLeast(dp(180))))
  val dialog=AlertDialog.Builder(this).setTitle(source.name+" · 아이콘")
   .setView(content).setNegativeButton("취소",null)
   .setNeutralButton("기본 아이콘"){_,_->
    if(storageIcons.clearSelection(source.id)){refreshStorageIcons();message("기본 아이콘을 적용했습니다.")}
   }.setPositiveButton("이미지 추가"){_,_->
    pendingIconSourceId=source.id
    navigation.edit().putString("pending_icon_source",source.id).apply()
    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
     type="image/*";addCategory(Intent.CATEGORY_OPENABLE)
     addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    },storageIconRequest)
   }.create()
  val choices=storageIcons.choices()
  val selected=storageIcons.selected(source.id,source.type)?.id
  fun fill(query:String) {
   grid.removeAllViews()
   choices.filter{query.isBlank()||it.label.contains(query,true)||it.id.contains(query,true)}
    .forEach { choice->
     val tile=vertical().apply {
      gravity=Gravity.CENTER
      setPadding(dp(4),dp(7),dp(4),dp(5))
      background=ripple(if(choice.id==selected)secondaryTint() else surface(),
       if(choice.id==selected)accent() else line(),12f)
      contentDescription=choice.label+" 아이콘 선택"
      isFocusable=true
      setOnClickListener {
       if(storageIcons.select(source.id,choice.id)) {
        dialog.dismiss();refreshStorageIcons();message(choice.label+" 아이콘을 적용했습니다.")
       }else message("아이콘 저장에 실패했습니다.")
      }
     }
     tile.addView(ImageView(this).apply {
      imageTintList=null;scaleType=ImageView.ScaleType.FIT_CENTER
      background=shape(Color.WHITE,line(),9f)
      setPadding(dp(3),dp(3),dp(3),dp(3))
      storageIcons.image(this,choice)
     },LinearLayout.LayoutParams(dp(48),dp(48)))
     tile.addView(label(choice.label,11f).apply {
      gravity=Gravity.CENTER;maxLines=2;ellipsize=TextUtils.TruncateAt.END
     },LinearLayout.LayoutParams(-1,-2))
     grid.addView(tile,android.widget.GridLayout.LayoutParams().apply {
      columnSpec=android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED,1f)
      width=0;height=dp(102);setMargins(dp(3),dp(3),dp(3),dp(3))
     })
    }
  }
  search.addTextChangedListener(object:android.text.TextWatcher {
   override fun beforeTextChanged(text:CharSequence?,start:Int,count:Int,after:Int){}
   override fun onTextChanged(text:CharSequence?,start:Int,before:Int,count:Int){fill(text?.toString().orEmpty().trim())}
   override fun afterTextChanged(text:android.text.Editable?){}
  })
  fill("")
  dialog.show()
  dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
 }
 private fun sourceSubtitle(source:LibrarySource):String {
  if(source.type=="bookoasis")return "BookOasis  ·  "+source.location
  if(source.type!="drive")return sourceType(source.type)+"  ·  "+source.location
  val kind=DriveFolderPickerPolicy.storageKind(source)
  val remote=runCatching{JSONObject(source.extra).optString("ckreader_remote_name")}
   .getOrDefault("").ifBlank{source.name}
  val shared=runCatching {JSONObject(source.extra).optString("team_drive").isNotBlank()}.getOrDefault(false)
  return if(kind=="RClone · GDS")"$kind ($remote)"
   else if(shared)"$kind ($remote) · Shared Drive"
   else "$kind ($remote) · 내 드라이브"
 }

 override fun onCreate(savedInstanceState:Bundle?) {
  setTheme(if(GlobalConfig.isDark(this))
   com.ckchoi.elibrary.common.R.style.Theme_CkReader_Cobalt
   else com.ckchoi.elibrary.common.R.style.Theme_CkReader_Heyclicky)
  super.onCreate(savedInstanceState)
  repository=LibraryRepository(this)
  // Existing Book Oasis sources must build the new API index on app upgrade;
  // users should not have to remove and re-register their credentials.
  val opdsMigration=getSharedPreferences("ckreader_opds_migration",MODE_PRIVATE)
  repository.sources().filter{it.type=="bookoasis"}.forEach { source ->
   val key="bookoasis_api_v1_6_65_"+source.id
   if(!opdsMigration.getBoolean(key,false))runCatching {
    BookOasisCatalogWorker.enqueue(applicationContext,source)
    opdsMigration.edit().putBoolean(key,true).apply()
   }.onFailure{android.util.Log.w("ckReader","BookOasis API cache migration scheduling failed",it)}
  }
  adapter=CatalogAdapter()
  val remembered=runCatching { navigation.getString("source_id",null) }.getOrNull()
  selectedSourceId=remembered?.takeIf { id -> repository.sources().any { it.id==id } }
  currentFolder=if(selectedSourceId!=null)runCatching { navigation.getString("folder","").orEmpty() }.getOrDefault("") else ""
  currentFolder=LibraryVisibility.visibleAncestor(currentFolder)
  bookOasisSearch=if(selectedSource()?.type=="bookoasis")runCatching { navigation.getString("bo_search","").orEmpty() }.getOrDefault("") else ""
  if(intent.getBooleanExtra("ckreader_open_fonts",false))page=Page.FONTS
  else if(savedInstanceState?.getBoolean("ckreader_restore_fonts",false)==true)page=Page.FONTS
  else if(!BuildConfig.LITE&&savedInstanceState?.getBoolean("ckreader_restore_google_oauth_guide",false)==true)
   page=Page.GOOGLE_OAUTH_GUIDE
  else if(savedInstanceState?.getBoolean("ckreader_restore_settings",false)==true)
   page=Page.SETTINGS
  try { render() } catch (error:Exception) {
   android.util.Log.e("ckReaderStartup","Initial render failed",error)
   selectedSourceId=null;currentFolder="";bookOasisSearch="";page=Page.LIBRARY
   try { render() } catch (retryError:Exception) {
    android.util.Log.e("ckReaderStartup","Recovery render failed",retryError)
    setContentView(android.widget.TextView(this).apply {
     text="ckReader 시작 오류. Android 로그에서 ckReaderStartup을 확인하세요. 저장된 데이터는 삭제되지 않았습니다."
     textSize=18f;setPadding(32,48,32,24)
    })
   }
  }
  if(!repository.isDocumentsCacheLinked()&&!repository.cacheFolderPrompted()){
   repository.markCacheFolderPrompted()
   window.decorView.post{
    if(!isDestroyed && !isFinishing)
     AlertDialog.Builder(this).setTitle("도서 캐시 저장 위치")
      .setMessage("도서정보와 작은 표지를 Documents/ckReader 폴더에 보관하려면 Documents 폴더를 한 번 선택해 주세요. 선택 전에는 앱 내부에 영구 저장됩니다.")
      .setNegativeButton("나중에",null)
      .setPositiveButton("Documents 선택"){_,_->chooseDocumentsCacheFolder()}
      .show()
   }
  }
 }
 override fun onSaveInstanceState(outState:Bundle) {
  outState.putBoolean("ckreader_restore_settings",page==Page.SETTINGS)
  outState.putBoolean("ckreader_restore_fonts",page==Page.FONTS)
  outState.putBoolean("ckreader_restore_google_oauth_guide",page==Page.GOOGLE_OAUTH_GUIDE)
  super.onSaveInstanceState(outState)
 }
 override fun onStart(){
  super.onStart()
  if(!metadataListening){
   ContextCompat.registerReceiver(this,metadataReceiver,IntentFilter(BookMetadataWorker.UPDATED),
    ContextCompat.RECEIVER_NOT_EXPORTED)
   ContextCompat.registerReceiver(this,cacheReceiver,IntentFilter(CacheRebuildService.FINISHED),
    ContextCompat.RECEIVER_NOT_EXPORTED)
   metadataListening=true
  }
 }
 override fun onStop(){
  exitGuard.reset()
  if(metadataListening){
   unregisterReceiver(metadataReceiver);unregisterReceiver(cacheReceiver)
   metadataListening=false
  }
  super.onStop()
 }
 override fun onResume() {
  super.onResume()
  cacheProgressHandler.removeCallbacks(cacheProgressPoll)
  cacheProgressHandler.post(cacheProgressPoll)
  // Background YAML work may have replaced folder covers while the receiver
  // was stopped. Rebind from SQLite when returning from another Activity.
  coverLoader.clearMemory()
  if(page in setOf(Page.SETTINGS,Page.ADD_SOURCE))render()
  if(page==Page.FONTS&&fontsRevision!=ReaderFonts.revision(this))render()
  if(page==Page.LIBRARY&&selectedSourceId==null)render()
  else if(page==Page.LIBRARY&&::adapter.isInitialized)adapter.notifyItemRangeChanged(0,adapter.itemCount)
  // Going back from a reader or returning to the Activity should reuse the
  // current folder's snapshot instead of requesting the server again.
  loadIfNotCached()
  if(page==Page.CACHE_REBUILD)loadCacheUsage()
  if(CacheRebuildService.running)
   window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
 }
 override fun onPause() {
  cacheProgressHandler.removeCallbacks(cacheProgressPoll)
  super.onPause()
 }
 private fun loadIfNotCached() {
  if(page!=Page.LIBRARY || selectedSource()==null || !::status.isInitialized)return
  if(!LibraryNavigationPolicy.needsFetch(lastListing))return
  if(scanTask?.isDone==false)return
  refresh()
 }
 private fun remember() {
  navigation.edit().putString("source_id",selectedSourceId).putString("folder",currentFolder)
   .putString("bo_search",bookOasisSearch).apply()
 }
 private fun showSource(source:LibrarySource,folder:String="",restoreOnBack:Boolean=false) {
  saveLibraryAnchor()
  if(source.id!=selectedSourceId){bookOasisSearch=""}
  selectedSourceId=source.id
  currentFolder=if(source.type=="local")"" else folder
  requestedBackAnchor=if(LibraryScrollPolicy.useHistory(restoreOnBack))
   navigationKey() else null
  remember()
  page=Page.LIBRARY
  render()
  loadIfNotCached()
 }
 private fun allSources() {
  saveLibraryAnchor()
  requestedBackAnchor=null
  selectedSourceId=null
  currentFolder=""
  page=Page.LIBRARY
  render()
 }
 private fun goBack() {
  when(page) {
   Page.SEARCH->{
    requestedBackAnchor=navigationKey()
    page=Page.LIBRARY;render();loadIfNotCached()
   }
   Page.BOOK_DETAIL->{
    ++detailLoadVersion
    detailTask?.cancel(true)
    detailLoading=false
    requestedBackAnchor=navigationKey()
    detailBook=null;page=Page.LIBRARY;render();loadIfNotCached()
   }
   Page.CACHE_REBUILD->{page=Page.SETTINGS;render()}
   Page.SETTINGS->{
    requestedBackAnchor=navigationKey()
    page=Page.LIBRARY;render();loadIfNotCached()
   }
   Page.FONTS->{page=Page.SETTINGS;render()}
   Page.GOOGLE_OAUTH_GUIDE->{page=Page.SETTINGS;render()}
   Page.MANAGE->{page=Page.SETTINGS;render()}
   Page.ADD_SOURCE->{page=addSourceBack;render()}
   Page.FORM->{
    if(formReturnPage==Page.LIBRARY)requestedBackAnchor=navigationKey()
    page=formReturnPage;render();loadIfNotCached()
   }
   Page.DRIVE_REMOTES->{page=Page.ADD_SOURCE;render()}
   Page.LIBRARY->{
    val source=selectedSource()
    if(source==null){
     if(exitGuard.press(android.os.SystemClock.elapsedRealtime()))finish()
     else Toast.makeText(this,"뒤로가기를 한 번 더 누르면 종료됩니다.",Toast.LENGTH_SHORT).show()
     return
    }
    if(currentFolder.isNotEmpty())showSource(source,currentFolder.substringBeforeLast('/',""),restoreOnBack=true)
    else allSources()
   }
  }
 }
 @Deprecated("Deprecated in Java")
 override fun onBackPressed(){goBack()}
 override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean {
  if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN)exitGuard.reset()
  return super.dispatchTouchEvent(event)
 }

 private fun render() {
  cacheProgressSummary=null
  cacheProgressCurrent=null
  cacheProgressCancel=null
  cacheProgressHistory=null
  if(page!=Page.LIBRARY||selectedSourceId!=null)exitGuard.reset()
  ++scanVersion
  scanTask?.cancel(true)
  autoLoadingBookOasis=false
  window.statusBarColor=paper()
  window.navigationBarColor=paper()
  @Suppress("DEPRECATION")
  window.decorView.systemUiVisibility=if(dark())0 else
   View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

  val main=vertical().apply {
   setBackgroundColor(paper())
   setPadding(dp(16),dp(12),dp(16),dp(10))
  }
  ViewCompat.setOnApplyWindowInsetsListener(main) { view,insets ->
   val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
   view.setPadding(dp(16)+bars.left,dp(12)+bars.top,dp(16)+bars.right,dp(10)+bars.bottom)
   WindowInsetsCompat.CONSUMED
  }
  val source=selectedSource()
  val heading=when(page) {
   Page.LIBRARY->source?.name ?: "내 서재"
   Page.SETTINGS->"설정"
   Page.FONTS->"글꼴 소개"
   Page.GOOGLE_OAUTH_GUIDE->"개인 Google OAuth 안내"
   Page.ADD_SOURCE->"저장소 추가"
   Page.FORM->if(editing==null)"저장소 연결" else "저장소 수정"
   Page.MANAGE->"저장소 관리"
   Page.DRIVE_REMOTES->"Google Drive 선택"
   Page.SEARCH->"전체 검색"
   Page.BOOK_DETAIL->"도서 상세"
   Page.CACHE_REBUILD->"캐시 재생성"
  }
  val hero=vertical().apply {
   background=ReaderDesign.hero(this@LibraryActivity,palette())
   setPadding(dp(13),dp(12),dp(13),dp(12))
  }
  val header=horizontal()
  if(page!=Page.LIBRARY || source!=null) {
   header.addView(iconButton(R.drawable.ic_arrow_back,"뒤로 가기"){goBack()})
  }
  header.addView(label(heading,25f).apply {
   maxLines=1;ellipsize=TextUtils.TruncateAt.END
   typeface=Typeface.create("sans-serif",Typeface.BOLD)
  },LinearLayout.LayoutParams(0,-2,1f))
  if(page==Page.LIBRARY) {
   header.addView(iconButton(R.drawable.ic_search,"전체 캐시 검색"){
    saveLibraryAnchor()
    page=Page.SEARCH;render()
   })
   header.addView(iconButton(R.drawable.ic_settings,"앱 설정"){
    saveLibraryAnchor()
    page=Page.SETTINGS;render()
   })
  }
  hero.addView(header)
  if(page !in setOf(Page.SETTINGS,Page.FONTS,Page.GOOGLE_OAUTH_GUIDE))hero.addView(label(when(page) {
   Page.LIBRARY->if(source==null)"내 책과 저장소를 한눈에" else "나만의 디지털 책장"
   Page.SETTINGS,Page.FONTS,Page.GOOGLE_OAUTH_GUIDE->""
   Page.ADD_SOURCE->"책이 있는 곳이면 어디든"
   Page.FORM->"연결 정보를 안전하게 관리하세요"
   Page.MANAGE->"모든 도서 저장소 한곳에"
   Page.DRIVE_REMOTES->"불러올 계정을 선택하세요"
   Page.SEARCH,Page.BOOK_DETAIL,Page.CACHE_REBUILD->""
  },12f,true))
  main.addView(hero,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(14)})

  when(page) {
   Page.LIBRARY->{
    if(source==null)renderSources(main) else renderLibrary(main,source)
   }
   Page.SETTINGS->renderSettings(main)
   Page.FONTS->renderFonts(main)
   Page.GOOGLE_OAUTH_GUIDE->renderGoogleOAuthGuide(main)
   Page.ADD_SOURCE->renderAddSource(main)
   Page.FORM->renderForm(main)
   Page.MANAGE->renderManage(main)
   Page.DRIVE_REMOTES->renderDriveRemotes(main)
   Page.SEARCH->renderGlobalSearch(main)
   Page.BOOK_DETAIL->renderBookDetail(main)
   Page.CACHE_REBUILD->renderCacheRebuild(main)
  }
  ReaderDesign.finishButtons(main)
  setContentView(main)
 }

 private fun installCatalog(main:LinearLayout,rows:List<Entry>) {
  val catalog=RecyclerView(this).apply {
   layoutManager=LinearLayoutManager(this@LibraryActivity)
   adapter=this@LibraryActivity.adapter
   itemAnimator=null
   setHasFixedSize(false)
   clipToPadding=false
   addOnScrollListener(object:RecyclerView.OnScrollListener() {
    override fun onScrollStateChanged(recyclerView:RecyclerView,newState:Int) {
     if(newState==RecyclerView.SCROLL_STATE_IDLE)
      coverLoader.retryVisible(recyclerView)
    }
   })
  }
  main.addView(catalog,LinearLayout.LayoutParams(-1,0,1f))
  catalogView=catalog
  adapter.submit(rows)
 }
 private fun renderSources(main:LinearLayout) {
  val recent=repository.recentlyOpened()
  status=label("등록된 저장소 "+repository.sources().size+"개  ·  저장소를 선택하세요",12f,true)
  main.addView(status)
  val rows=mutableListOf<Entry>(Entry.Heading("RECENT  /  최근 본 도서"))
  if(recent.isEmpty())rows.add(Entry.Notice("최근에 열어본 도서가 없습니다."))
  recent.forEach{rows.add(Entry.Recent(it))}
  rows.add(Entry.Heading("STORAGES  /  저장소"))
  val sources=repository.sources()
  if(sources.isEmpty())rows.add(Entry.Notice("등록된 저장소가 없습니다. 로컬 폴더 또는 네트워크 저장소를 추가하세요."))
  sources.forEach { rows.add(Entry.Storage(it)) }
  rows.add(Entry.AddStorage)
  installCatalog(main,rows)
 }
 private fun renderLibrary(main:LinearLayout,source:LibrarySource) {
  lastListing=null
  val trail=horizontal()
  trail.addView(action("전체 저장소"){allSources()})
  trail.addView(label("›",17f,true))
  trail.addView(action(source.name){showSource(source)})
  var path=""
  if(source.type!="local") {
   for(part in currentFolder.split('/').filter { it.isNotBlank() }) {
    path=if(path.isEmpty())part else path+"/"+part
    val step=path
    trail.addView(label("›",17f,true))
    trail.addView(action(if(source.type=="bookoasis")BookOasisPaths.display(part) else part){showSource(source,step)})
   }
  }
  // HorizontalScrollView keeps deep paths scrollable on narrow devices.
  val horizontalScroll=android.widget.HorizontalScrollView(this).apply {
   isHorizontalScrollBarEnabled=false
   addView(trail)
  }
  main.addView(horizontalScroll,LinearLayout.LayoutParams(-1,dp(58)))
  if(source.type=="bookoasis") {
   val searchBar=horizontal()
   val query=EditText(this).apply {
    hint="북오아시스 전체 검색"
    textSize=15f;setSingleLine(true);setText(bookOasisSearch)
    setTextColor(ink());setHintTextColor(muted())
    background=shape(surface(),line(),13f);setPadding(dp(12),0,dp(12),0)
    imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
   }
   fun doSearch() {
    val value=query.text.toString().trim().take(200)
    saveLibraryAnchor()
    requestedBackAnchor=null
    bookOasisSearch=value
    currentFolder="" // Search across all BookOasis libraries.
    remember();render();refresh()
   }
   query.setOnEditorActionListener { _,actionId,_->
    if(actionId==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){doSearch();true}else false
   }
   searchBar.addView(query,LinearLayout.LayoutParams(0,dp(48),1f))
   searchBar.addView(action("검색",true){doSearch()})
   if(bookOasisSearch.isNotBlank())searchBar.addView(action("초기화") {
    saveLibraryAnchor()
    requestedBackAnchor=null
    bookOasisSearch="";currentFolder="";remember();render();refresh()
   })
   main.addView(searchBar,LinearLayout.LayoutParams(-1,dp(60)))
  }
  val toolbar=horizontal()
  toolbar.addView(label(if(source.type=="local")"하위 폴더 포함 전체 검색"
   else if(source.type=="bookoasis")"북오아시스 카테고리 · 시리즈 · 도서"
   else "현재 폴더의 도서만 표시",12f,true),
   LinearLayout.LayoutParams(0,-2,1f))
  toolbar.addView(iconButton(R.drawable.ic_refresh,"현재 폴더 새로고침"){
   confirmFolderRefresh()
  })
  main.addView(toolbar)
  status=label("폴더 목록 확인 중...",12f,true)
  main.addView(status)
  val cached=repository.cached(source,currentFolder,bookOasisSearch)
  installCatalog(main,listOf(Entry.Notice("도서와 폴더를 불러오는 중입니다.")))
  if(cached!=null) {
   showListing(source,cached,true)
   // Older snapshots stored series cover URLs only. Backfill author/publisher
   // in the background once per open category without clearing the images.
   val series=cached.folders.filter{it.startsWith("s~")}
   val key=source.id+"|"+currentFolder+"|"+bookOasisSearch
   if(source.type=="bookoasis"&&series.isNotEmpty()&&
      series.any{it !in cached.folderInfos}&&seriesInfoBackfills.add(key)) {
    val expectedFolder=currentFolder
    val expectedSearch=bookOasisSearch
    window.decorView.post {
     if(!isDestroyed&&page==Page.LIBRARY&&selectedSourceId==source.id &&
      currentFolder==expectedFolder&&bookOasisSearch==expectedSearch)refresh()
    }
   }
  }
 }

 private fun showListing(source:LibrarySource,result:LibraryListing,fromCache:Boolean,
                         scheduleMetadata:Boolean=true) {
  if(page!=Page.LIBRARY || selectedSourceId!=source.id)return
  // Always sort the *displayed* titles, including cache hits, web metadata
  // updates, BookOasis pagination and titles newly read from YAML files.
  val visible=LibraryTitleOrder.listing(result,source.type)
  lastListing=visible
  status.text=(if(fromCache)"저장된 목록 · " else "최신 목록 · ")+
   visible.folders.size+"개 "+(if(source.type=="bookoasis")"카테고리/시리즈" else "폴더")+
   " / "+visible.books.size+"권"
  val rows=mutableListOf<Entry>()
  if(source.type!="local" && visible.folders.isNotEmpty()) {
   rows.add(Entry.Heading(if(source.type=="bookoasis")
    "BOOKOASIS  /  카테고리 및 시리즈" else "FOLDERS  /  하위 폴더"))
   visible.folders.forEach { rows.add(Entry.Folder(it)) }
  }
  rows.add(Entry.Heading("BOOKS  /  "+(if(source.type=="local")"전체 도서" else "현재 폴더의 도서")))
  visible.books.forEachIndexed { index,book -> rows.add(Entry.Book(book,index+1)) }
  if(visible.hasMore&&source.type=="bookoasis") {
   if(autoLoadingBookOasis)rows.add(Entry.Notice("다음 북오아시스 목록을 자동으로 불러오는 중입니다..."))
   else rows.add(Entry.LoadMore)
  }
  if(visible.books.isEmpty()&&visible.folders.isEmpty())rows.add(Entry.Notice(
   if(source.type=="bookoasis")"조회 결과가 없습니다. 다른 카테고리 또는 검색어를 선택하세요."
   else "이 위치에 지원하는 도서 파일이 없습니다."
  ))
  val currentAnchor=currentVisibleAnchor()
  adapter.submit(rows)
  if(requestedBackAnchor==navigationKey()){
   restoreAnchor(scrollAnchors[navigationKey()],rows)
   requestedBackAnchor=null
  } else if(currentAnchor!=null) {
   // Data refresh/re-sort inside SAME folder should preserve the visible book.
   restoreAnchor(currentAnchor,rows)
  } else {
   (catalogView?.layoutManager as? LinearLayoutManager)
    ?.scrollToPositionWithOffset(0,0)
  }
  if(scheduleMetadata&&visible.books.isNotEmpty())
   BookMetadataWorker.enqueue(applicationContext,source,currentFolder,
    if(source.type=="bookoasis")bookOasisSearch else "",visible)
 }
 private fun fetchBookOasisPages(source:LibrarySource,folder:String,query:String,version:Int,
  seed:LibraryListing?=null,preserveCompleteCache:Boolean=false,
  scheduleMetadata:Boolean=true) {
  scanTask=worker.submit {
   val result=runCatching {
    repository.browseBookOasisComplete(source,folder,query,seed,
     onProgress={partial ->
      runOnUiThread {
       if(isDestroyed || page!=Page.LIBRARY || version!=scanVersion ||
        selectedSourceId!=source.id || currentFolder!=folder || query!=bookOasisSearch)
        return@runOnUiThread
       // Keep the complete cached list visible during a background refresh.
       if(!preserveCompleteCache || !partial.hasMore)
        showListing(source,partial,false,false)
       if(partial.hasMore)
        status.text="시리즈 목록 자동 로딩 중 · ${partial.folders.size}개 · ${partial.page}페이지"
      }
     },
     cancelled={Thread.currentThread().isInterrupted || version!=scanVersion})
   }
   runOnUiThread {
    if(isDestroyed || page!=Page.LIBRARY || version!=scanVersion ||
     selectedSourceId!=source.id || currentFolder!=folder || query!=bookOasisSearch)
     return@runOnUiThread
    autoLoadingBookOasis=false
    result.onSuccess {
     showListing(source,it,false,scheduleMetadata)
     if(!it.hasMore)status.text="북오아시스 목록 로딩 완료 · 시리즈 ${it.folders.size}개"
    }.onFailure {failure ->
     val partial=lastListing
     if(partial?.hasMore==true)showListing(source,partial,false,false)
     status.text="북오아시스 추가 목록 로딩 실패: "+(failure.message?:"알 수 없는 오류")+
      if(partial?.hasMore==true)" · 더 보기로 재시도" else " · 새로고침으로 재시도"
     if(partial==null)adapter.submit(listOf(Entry.Notice("북오아시스 목록을 불러오지 못했습니다.")))
    }
   }
  }
 }

 private fun confirmFolderRefresh() {
  if(page!=Page.LIBRARY)return
  val source=selectedSource()?:return
  val folder=currentFolder
  AlertDialog.Builder(this).setMessage(LibraryRefreshPolicy.MESSAGE)
   .setNegativeButton("취소",null)
   .setNeutralButton("아니오"){_,_->
    if(page==Page.LIBRARY&&selectedSourceId==source.id&&currentFolder==folder)
     refresh(scheduleMetadata=false)
   }
   .setPositiveButton("예"){_,_->
    if(page!=Page.LIBRARY||selectedSourceId!=source.id||currentFolder!=folder)
     return@setPositiveButton
    val selection=CacheRebuildService.Selection(source.id,
     LibraryRefreshPolicy.depth(source.type,folder))
    runCatching {
     CacheRebuildService.start(applicationContext,listOf(selection),
      CacheRebuildService.StartMode.REPLACE,forceRebuild=cacheRebuildForce)
    }.onSuccess {
     status.text="현재 폴더와 하위 폴더의 캐시 생성 작업을 등록했습니다."
     message("기존 캐시 작업을 중단하고 현재 폴더의 캐시 작업을 시작합니다.")
    }.onFailure{error->
     message("캐시 생성 시작 실패: "+(error.message?:"서비스 오류"))
    }
   }.show()
 }

 private fun refresh(scheduleMetadata:Boolean=true) {
  if(page!=Page.LIBRARY)return
  val source=selectedSource() ?: return
  val folder=currentFolder
  val version=++scanVersion
  scanTask?.cancel(true)
  val query=if(source.type=="bookoasis")bookOasisSearch else ""
  autoLoadingBookOasis=source.type=="bookoasis"
  val cached=repository.cached(source,folder,query)
  if(cached!=null) {
   showListing(source,cached,true,scheduleMetadata)
   status.text="저장된 목록 표시 중 · 최신 목록 확인 중"
  }else {
   status.text="저장소를 탐색하는 중..."
  }
  if(source.type=="bookoasis") {
   fetchBookOasisPages(source,folder,query,version,
    preserveCompleteCache=cached!=null && !cached.hasMore,
    scheduleMetadata=scheduleMetadata)
   return
  }
  scanTask=worker.submit {
   var lastFolderReport=0L
   val result=runCatching {
    repository.browse(source,folder,query,
     onRemoteFolderProgress={checked,total,reads->
      val now=android.os.SystemClock.elapsedRealtime()
      if(now-lastFolderReport>=600L || checked==total) {
       lastFolderReport=now
       runOnUiThread {
        if(!isDestroyed && page==Page.LIBRARY && version==scanVersion &&
           selectedSourceId==source.id && currentFolder==folder)
         status.text="도서 없는 폴더 확인 중 · "+checked+"/"+total+
          " · 하위 폴더 "+reads+"개 확인"
       }
      }
     })
   }
   runOnUiThread {
    if(isDestroyed || page!=Page.LIBRARY ||
      version!=scanVersion || source.id!=selectedSourceId || currentFolder!=folder ||
      (source.type=="bookoasis"&&query!=bookOasisSearch))return@runOnUiThread
    result.onSuccess { showListing(source,it,false,scheduleMetadata) }
     .onFailure { error ->
      status.text=if(cached!=null)"연결 실패 · 저장된 목록 표시 중: "+(error.message ?: "알 수 없는 오류")
       else "목록을 불러올 수 없습니다: "+(error.message ?: "알 수 없는 오류")
      if(cached==null)adapter.submit(listOf(Entry.Notice("저장소 연결을 확인하거나 다시 새로고침하세요.")))
     }
   }
  }
 }

 private fun loadMore() {
  val source=selectedSource() ?:return
  val current=lastListing ?:return
  if(source.type!="bookoasis"||!current.hasMore||autoLoadingBookOasis)return
  val folder=currentFolder;val query=bookOasisSearch
  val version=++scanVersion
  scanTask?.cancel(true)
  autoLoadingBookOasis=true
  showListing(source,current,true,false)
  status.text="중단된 목록 조회 재개 중 · ${current.folders.size}개"
  fetchBookOasisPages(source,folder,query,version,seed=current)
 }
 private fun entryRow(id:Int,title:String,sub:String,
  decorateIcon:((ImageView)->Unit)?=null,onClick:()->Unit):LinearLayout {
  val row=horizontal().apply {
   layoutParams=cardLayoutParams()
   minimumHeight=dp(80)
   setPadding(dp(12),dp(10),dp(10),dp(10))
   background=ripple(surface(),line(),17f)
   elevation=if(dark())0f else dp(1).toFloat()
   isFocusable=true;isClickable=true
   setOnClickListener {onClick()}
   contentDescription=title
  }
  val artwork=icon(id,26,accent())
  if(decorateIcon==null) {
   artwork.background=shape(secondaryTint(),secondaryTint(),11f)
   artwork.setPadding(dp(9),dp(9),dp(9),dp(9))
  }
  decorateIcon?.invoke(artwork)
  row.addView(artwork,if(decorateIcon!=null)
   LinearLayout.LayoutParams(dp(56),dp(72))
   else LinearLayout.LayoutParams(dp(46),dp(46)))
  val textColumn=vertical().apply {setPadding(dp(10),0,dp(5),0)}
  textColumn.addView(label(title,16f).apply {
   typeface=Typeface.create("sans-serif",Typeface.BOLD)
   maxLines=2;ellipsize=TextUtils.TruncateAt.END
  })
  if(sub.isNotBlank())textColumn.addView(label(sub,12f,true).apply {
   maxLines=2;ellipsize=TextUtils.TruncateAt.END
  })
  row.addView(textColumn,LinearLayout.LayoutParams(0,-2,1f))
  row.addView(icon(R.drawable.ic_chevron_right,20,muted()))
  return row
 }
 private fun apiKeyRow(code:String,title:String,status:String,onClick:()->Unit):LinearLayout {
  val row=entryRow(R.drawable.ic_settings,title,status,onClick=onClick)
  row.removeViewAt(0)
  row.addView(TextView(this).apply {
   text=code
   textSize=24f
   gravity=Gravity.CENTER
   typeface=Typeface.create("sans-serif",Typeface.BOLD)
   setTextColor(palette().brandText())
   background=shape(accent(),accent(),11f)
   contentDescription=title+" 아이콘 "+code
  },0,LinearLayout.LayoutParams(dp(46),dp(46)))
  return row
 }
 private inner class CatalogAdapter : RecyclerView.Adapter<Holder>() {
  private var entries:List<Entry> = emptyList()
  fun submit(value:List<Entry>) {
   entries=value
   notifyDataSetChanged()
  }
  fun entryAt(index:Int):Entry?=entries.getOrNull(index)
  fun positionOfBook(id:String):Int=entries.indexOfFirst{
   it is Entry.Book && it.value.id==id
  }
  fun positionOfFolder(name:String):Int=entries.indexOfFirst{it is Entry.Folder&&it.name==name}
  override fun getItemCount()=entries.size
  override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):Holder {
   return Holder(vertical().apply {layoutParams=RecyclerView.LayoutParams(-1,-2)})
  }
  override fun onBindViewHolder(holder:Holder,position:Int) {
   val panel=holder.panel
   panel.removeAllViews()
   val entry=entries[position]
   panel.setBackgroundColor(Color.TRANSPARENT)
   panel.setPadding(0,0,0,0)
   when(entry) {
    is Entry.Heading->panel.addView(section(entry.value))
    is Entry.Storage->{
     val source=entry.source
     // Library cards use precisely two lines: saved name, then provider kind.
     val row=entryRow(sourceIcon(source.type),
      source.name,DriveFolderPickerPolicy.storageKind(source),decorateIcon={artwork->
       bindStorageIcon(artwork,source)
       artwork.contentDescription="저장소 "+source.name+" 아이콘 변경"
       artwork.setOnClickListener{showStorageIconPicker(source)}
      }) {showSource(source)}
     row.getChildAt(0).layoutParams=LinearLayout.LayoutParams(dp(52),dp(52))
     (row.getChildAt(1) as? LinearLayout)?.let {texts->
      for(i in 0 until texts.childCount) {
       (texts.getChildAt(i) as? TextView)?.apply{
        maxLines=1
        ellipsize=TextUtils.TruncateAt.END
       }
      }
     }
     row.addView(iconButton(R.drawable.ic_edit,"저장소 "+source.name+" 수정"){
      editing=source;formReturnPage=Page.LIBRARY;formType=source.type;formPreset="";page=Page.FORM;render()
     })
     panel.addView(row)
    }
    is Entry.Recent->panel.addView(recentBookRow(entry.value))
    is Entry.Folder->{
     val source=selectedSource()
     val name=entry.name
     val folder=if(currentFolder.isEmpty())name else currentFolder+"/"+name
     val thumbnailSupported=source?.type in setOf("bookoasis","drive","ftp","sftp","webdav")
     val hint=lastListing?.folderCovers?.get(name).orEmpty()
     val info=lastListing?.folderInfos?.get(name)
     val description=if(source?.type=="bookoasis"){
      buildList{
       info?.author?.takeIf(String::isNotBlank)?.let{add("저자: "+it)}
       info?.publisher?.takeIf(String::isNotBlank)?.let{add("출판사: "+it)}
       info?.volumeCount?.takeIf{it>0}?.let{add("총 "+it+"권")}
      }.joinToString(" · ").ifBlank{"도서 시리즈 목록 열기"}
     } else "폴더 열기"
     val folderRow=entryRow(R.drawable.ic_folder,
      if(source?.type=="bookoasis")BookOasisPaths.display(name) else name,
      description,
      decorateIcon=if(thumbnailSupported && source!=null) {icon ->
       icon.scaleType=ImageView.ScaleType.FIT_CENTER
       icon.setPadding(dp(4),dp(4),dp(4),dp(4))
       icon.background=shape(paper())
       icon.clipToOutline=true
       loadFolderThumbnail(source,folder,hint,icon)
      } else null) {
      if(source!=null)showSource(source,folder)
     }
     // Only offer the context action when an actual kavita.yaml/yml exists.
     if(source!=null)folderRow.setOnLongClickListener {
      inspectFolderYaml(source,folder)
      true
     }
     panel.addView(folderRow)
    }
    is Entry.Book->{
     panel.addView(bookRow(entry.value,entry.number))
    }
    is Entry.Notice->panel.addView(label(entry.message,15f).apply {
     setPadding(dp(12),dp(26),dp(12),dp(26))
    })
    Entry.LoadMore->{
     panel.addView(entryRow(R.drawable.ic_refresh,"더 보기",
      "다음 북오아시스 시리즈 목록 불러오기") {loadMore()})
    }
    Entry.AddStorage->{
     panel.addView(entryRow(R.drawable.ic_add,"저장소 추가",
      if(BuildConfig.LITE)"로컬 · WebDAV · FTP · RClone · 북오아시스"
      else "로컬 · WebDAV · FTP · Google Drive · RClone · 북오아시스") {
      addSourceBack=Page.LIBRARY;page=Page.ADD_SOURCE;render()
     })
    }
   }
   ReaderDesign.finishButtons(panel)
  }
 }
 private fun recentBookRow(recent:RecentBook):View {
  val book=recent.book
  val card=vertical().apply {
   layoutParams=cardLayoutParams()
   background=ripple(surface(),line(),17f)
   elevation=if(dark())0f else dp(1).toFloat()
   setPadding(dp(12),dp(12),dp(12),dp(12))
   isClickable=true;isFocusable=true
   contentDescription="도서 상세 "+book.shownTitle+", 저장소 "+book.source.name
   setOnClickListener{showBookDetail(book,recent.folder)}
  }
  val information=horizontal().apply{gravity=Gravity.TOP}
  val cover=ImageView(this).apply {
   setImageResource(R.drawable.ic_book)
   imageTintList=ColorStateList.valueOf(muted())
   scaleType=ImageView.ScaleType.FIT_CENTER
   background=shape(secondaryTint(),line(),10f)
   clipToOutline=true
   setPadding(dp(3),dp(3),dp(3),dp(3))
  }
  information.addView(cover,LinearLayout.LayoutParams(dp(58),dp(80)))
  loadCover(book,cover)
  val details=vertical().apply{setPadding(dp(11),0,0,0)}
  details.addView(label(book.shownTitle,16f).apply {
   typeface=Typeface.create("sans-serif",Typeface.BOLD)
   maxLines=2;ellipsize=TextUtils.TruncateAt.END
  })
  details.addView(label("저장소: "+book.source.name+" · "+DriveFolderPickerPolicy.storageKind(book.source),12f,true).apply {
   maxLines=1;ellipsize=TextUtils.TruncateAt.END
  })
  if(book.author.isNotBlank())details.addView(label("저자: "+book.author,12f,true).apply {
   maxLines=1;ellipsize=TextUtils.TruncateAt.END
  })
  details.addView(label(SimpleDateFormat("yyyy.MM.dd HH:mm",Locale.KOREA).format(Date(recent.openedAt)),11f,true))
  information.addView(details,LinearLayout.LayoutParams(0,-2,1f))
  card.addView(information)
  val buttons=horizontal().apply{setPadding(0,dp(8),0,0)}
  buttons.addView(action("바로보기",true){openBook(book,recent.folder)},
   LinearLayout.LayoutParams(0,dp(44),1f))
  buttons.addView(action("삭제"){repository.removeRecentlyOpened(book.id);render()},
   LinearLayout.LayoutParams(dp(78),dp(44)).apply{marginStart=dp(8)})
  card.addView(buttons)
  return card
 }
 private fun bookRow(book:LibraryBook,number:Int):View {
  // Cached listings were already enriched when read; never block rapid
  // RecyclerView scrolling on repeated SQLite metadata lookups.
  val row=horizontal().apply {
   layoutParams=cardLayoutParams()
   gravity=Gravity.TOP
   background=ripple(surface(),line(),17f)
   elevation=if(dark())0f else dp(1).toFloat()
   setPadding(dp(10),dp(15),dp(10),dp(15))
  }
  row.addView(label("%03d".format(number),11f,true).apply {setTextColor(accent())},
   LinearLayout.LayoutParams(dp(37),-2))
  val cover=ImageView(this).apply {
   setImageResource(R.drawable.ic_book)
   imageTintList=ColorStateList.valueOf(muted())
   scaleType=ImageView.ScaleType.FIT_CENTER
   background=shape(secondaryTint(),line(),10f)
   clipToOutline=true
   setPadding(dp(3),dp(3),dp(3),dp(3))
  }
  row.addView(cover,LinearLayout.LayoutParams(dp(58),dp(80)))
  val body=vertical().apply {setPadding(dp(10),0,0,0)}
  val title=label(book.shownTitle,17f).apply {
   typeface=Typeface.create("sans-serif",Typeface.BOLD)
   maxLines=3;ellipsize=TextUtils.TruncateAt.END
  }
  title.setOnClickListener {
   if(page==Page.SEARCH)openBook(book) else showBookDetail(book)
  }
  row.setOnClickListener {
   if(page==Page.SEARCH)openBook(book) else showBookDetail(book)
  }
  val heading=horizontal().apply{gravity=Gravity.CENTER_VERTICAL}
  heading.addView(title,LinearLayout.LayoutParams(0,-2,1f))
  val sourceIcon=MetadataSourceBadge.code(book.metadataSource)
  if(sourceIcon in listOf("K","Y","A","N","L","B"))heading.addView(TextView(this).apply {
   text=sourceIcon
   tag="metadata-source:"+sourceIcon
   textSize=19f
   gravity=Gravity.CENTER
   setTextColor(palette().brandText())
   typeface=Typeface.create("sans-serif",Typeface.BOLD)
   background=shape(accent(),accent(),10f)
   contentDescription="도서정보 출처 "+MetadataSourceBadge.label(sourceIcon)
  },LinearLayout.LayoutParams(dp(38),dp(38)).apply{marginStart=dp(7)})
  body.addView(heading)
  if(page==Page.SEARCH)body.addView(label("저장소: "+book.source.name,12f,true))
  if(book.title.isNotBlank() || book.author.isNotBlank() || book.publisher.isNotBlank()) {
   body.addView(label("AUTHOR: "+book.author.ifBlank { "미확인" },11f,true))
   if(book.publisher.isNotBlank())body.addView(label("PUBLISHER: "+book.publisher,11f,true))
   if(book.bookId.isNotBlank())body.addView(label("BOOK ID: "+book.bookId,11f,true))
  }
  if(book.seriesName.isNotBlank())body.addView(label("SERIES: "+book.seriesName+
   (if(book.seriesIndex>0)" · "+book.seriesIndex+"권" else "")+
   (if(book.seriesTotal>0)" / "+book.seriesTotal+"권" else ""),11f,true))
  if(book.translator.isNotBlank())body.addView(label("TRANSLATOR: "+book.translator,11f,true))
  if(book.isbn.isNotBlank())body.addView(label("ISBN: "+book.isbn,11f,true))
  if(book.publishedDate.isNotBlank())body.addView(label("출간일: "+book.publishedDate,11f,true))
  if(book.price.isNotBlank())body.addView(label("가격: "+book.price+"원",11f,true))
  if(book.genre.isNotBlank())body.addView(label("장르: "+book.genre,12f,true))
  if(book.summary.isNotBlank())body.addView(label(book.summary,13f).apply{
   maxLines=3;ellipsize=TextUtils.TruncateAt.END
  })
  if(book.metadataSource.isBlank()&&repository.metadataEnabled()&&
     book.cover.isBlank()&&book.webCover.isBlank())
   body.addView(label("표지가 없는 도서만 웹에서 자동 보완합니다",11f,true))
  if(book.metadataSource.isNotBlank())body.addView(label(
   "도서정보 출처 · "+book.metadataSource,11f,true).apply {
    setTextColor(accent())
    if(book.metadataUrl.startsWith("https://")){
     setOnClickListener {
      runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(book.metadataUrl)))}
       .onFailure {message("정보 출처 링크를 열 수 없습니다.")}
     }
    }
   })
  body.addView(label("FORMAT: "+book.format.uppercase()+"  /  "+book.filename,11f,true))
  val last=repository.lastRead(book.id)
  body.addView(label("LAST READ: "+if(last==0L)"—" else SimpleDateFormat("yyyy.MM.dd HH:mm",Locale.KOREA).format(Date(last)),11f,true))
  // Weighted actions keep all three controls visible on narrow phones.
  val controls=horizontal()
  controls.addView(action("읽기",true){openBook(book)}.apply {
   minWidth=0;minimumWidth=0;setPadding(dp(4),0,dp(4),0)
  },LinearLayout.LayoutParams(0,dp(48),1f))
  controls.addView(iconButton(R.drawable.ic_refresh,"표지·도서정보 새로고침 · K/Y/A/N/L 우선 조회"){
   refreshBookCover(book)
  }.apply {isEnabled=book.id !in refreshingCovers},
   LinearLayout.LayoutParams(dp(48),dp(48)))
  if(book.source.type!="bookoasis"&&!book.source.readOnly&&
   !DriveAccess.google(book.source))controls.addView(action("삭제"){
   confirmDelete(book)
  }.apply {
   minWidth=0;minimumWidth=0;setPadding(dp(4),0,dp(4),0)
  },LinearLayout.LayoutParams(0,dp(48),1f))
  body.addView(controls)
  row.addView(body,LinearLayout.LayoutParams(0,-2,1f))
  // Display a durable DB cover immediately if present. Otherwise lazily
  // discover and persist embedded artwork only for visible RecyclerView rows.
  loadCover(book,cover)
  return row
 }
 /**
  * Explicit book-card refresh uses K -> Y -> A -> N -> L, stores results in
  * SQLite, then optionally writes/organizes kavita.yaml with permission checks.
  * Existing Book Oasis OPDS metadata continues using its authoritative server.
  */
 private fun refreshBookCover(book:LibraryBook) {
  if(!refreshingCovers.add(book.id))return
  val source=book.source
  val folder=currentFolder
  val originalId=book.id
  val originalPath=book.path
  coverLoader.invalidate(CatalogCoverKey.book(source.id,book.id,book.path))
  val current=adapter.positionOfBook(book.id)
  if(current>=0)adapter.notifyItemChanged(current)
  if(page==Page.LIBRARY&&selectedSourceId==source.id&&::status.isInitialized)
   status.text="도서정보 확인 중 · K → Y → A → N → L · "+book.shownTitle.take(35)
  coverRefreshWorker.submit {
   val result=runCatching {
    val updated=repository.refreshBookCardMetadata(book)
    if(source.type=="bookoasis")repository.refreshBookOasisSeriesThumbnail(book,folder)
    val listing=if(updated.updated)
     runCatching{repository.browse(source,folder)}.getOrNull()
    else repository.cached(source,folder)
    updated to listing
   }
   runOnUiThread {
    refreshingCovers.remove(originalId)
    if(isDestroyed)return@runOnUiThread
    coverLoader.invalidate(CatalogCoverKey.book(source.id,originalId,originalPath))
    if(page==Page.LIBRARY && selectedSourceId==source.id&&currentFolder==folder){
     result.onSuccess{(updated,listing)->
      if(listing!=null)showListing(source,listing,false,false)
      else {
       val index=adapter.positionOfBook(originalId)
       if(index>=0)adapter.notifyItemChanged(index)
      }
      val statusText=if(updated.updated)
       "도서정보 캐시 업데이트 완료 · "+updated.sourceCode+
        (if(updated.wroteOrMoved)" · kavita.yaml 생성/도서 정리" else "")
       else "공식 자료에서 일치하는 도서정보를 확인하지 못했습니다."
      message(statusText)
     }.onFailure{failure->
      android.util.Log.w("ckReader","Ordered book refresh failed",failure)
      message("도서정보 새로고침 실패: "+(failure.message?:"조회 오류"))
     }
     if(::status.isInitialized)status.text=lastListing?.let{listing->
      "폴더 "+listing.folders.size+"개 · 도서 "+listing.books.size+"권"
     }?:"도서정보 새로고침 완료"
    }
   }
  }
 }

 /** Thumbnail retrieval and leaf-folder detection never block the main thread. */
 private fun loadFolderThumbnail(source:LibrarySource,path:String,coverHint:String,target:ImageView) {
  val key=CatalogCoverKey.folder(source.id,path)
  coverLoader.bind(target,key,
   cached={repository.cachedFolderThumbnail(source,path,coverHint)},
   resolve={repository.folderThumbnail(source,path,coverHint)})
 }

 private fun loadCover(book:LibraryBook,target:ImageView,detail:Boolean=false) {
  val key=CatalogCoverKey.book(book.source.id,book.id,book.path)
  coverLoader.bind(target,key,
   cached={repository.cachedBookThumbnail(book)},
   // Both list and detail load from the same durable book-cover cache.
   // A cache miss in detail must also resolve the cover, not stop at null.
   resolve={repository.cover(book)})
 }
 private fun openBook(book:LibraryBook,folderOverride:String?=null) {
  // Stop recursive remote folder checks before a foreground download, so they
  // cannot enqueue more rclone RPCs ahead of the selected book transfer.
  scanTask?.cancel(true)
  val seq=++bookOpenSequence
  val openedFolder=folderOverride ?: if(page==Page.BOOK_DETAIL)detailSeriesPath
   else if(selectedSourceId==book.source.id)currentFolder
   else book.path.substringBeforeLast('/',"")
  bookOpenTask?.cancel(true)
  bookOpenTicker?.let(bookOpenHandler::removeCallbacks)
  bookOpenDialog?.dismiss()
  val started=android.os.SystemClock.elapsedRealtime()
  var currentStage="원격 도서 정보 확인 중"
  var transferred=-1L
  var totalBytes=-1L
  val panel=LinearLayout(this).apply {
   orientation=LinearLayout.VERTICAL
   setPadding(dp(22),dp(12),dp(22),dp(12))
  }
  val stageView=TextView(this).apply {
   textSize=14f;setTextColor(ink());maxLines=3
   text=currentStage
  }
  val progressBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
   max=1000;isIndeterminate=true
  }
  val elapsedView=TextView(this).apply {
   textSize=12f;setTextColor(muted())
  }
  panel.addView(stageView,LinearLayout.LayoutParams(-1,-2).apply{
   bottomMargin=dp(16)
  })
  panel.addView(progressBar,LinearLayout.LayoutParams(-1,dp(12)))
  panel.addView(elapsedView,LinearLayout.LayoutParams(-1,-2).apply{
   topMargin=dp(10)
  })
  val dialog=AlertDialog.Builder(this)
   .setTitle("도서 여는 중 · "+book.filename.take(45))
   .setView(panel)
   .setNegativeButton("취소"){_,_->
    if(seq==bookOpenSequence) {
     ++bookOpenSequence
     bookOpenTask?.cancel(true)
     bookOpenTask=null
    }
   }.create().apply {
    setCancelable(false)
   }
  bookOpenDialog=dialog
  dialog.show()
  val show:()->Unit={
   val seconds=((android.os.SystemClock.elapsedRealtime()-started)/1000).coerceAtLeast(0)
   stageView.text=BookOpenProgress.label(currentStage,transferred,totalBytes)
   progressBar.isIndeterminate=transferred<0 || totalBytes<=0
   if(!progressBar.isIndeterminate){
    progressBar.progress=(transferred.coerceIn(0,totalBytes)*1000/totalBytes).toInt()
   }
   elapsedView.text="경과 "+seconds+"초 · 언제든 취소할 수 있습니다."
  }
  val ticker=object:Runnable {
   override fun run() {
    if(isDestroyed||seq!=bookOpenSequence||!dialog.isShowing)return
    show()
    bookOpenHandler.postDelayed(this,1000)
   }
  }
  bookOpenTicker=ticker
  bookOpenHandler.post(ticker)
  dialog.setOnDismissListener{bookOpenHandler.removeCallbacks(ticker)}
  val reportStage:(String)->Unit={stage->
   runOnUiThread {
    if(seq==bookOpenSequence && !isDestroyed && dialog.isShowing){
     if(currentStage!=stage){
      currentStage=stage
      transferred=-1L;totalBytes=-1L
     }
     show()
    }
   }
  }
  val reportProgress:(Long,Long)->Unit={received,total->
   runOnUiThread {
    if(seq==bookOpenSequence && !isDestroyed && dialog.isShowing) {
     transferred=received
     totalBytes=total
     show()
    }
   }
  }
  bookOpenTask=bookOpenWorker.submit {
   val result=runCatching {
    reportStage("도서 열기 방식 확인 중")
    val pages=if(book.source.type=="bookoasis"&&book.format in setOf("zip","cbz"))
     repository.bookOasisPages(book) else 0
    val uri=repository.open(book,reportProgress,reportStage)
    uri to pages
   }
   runOnUiThread {
    if(isDestroyed||seq!=bookOpenSequence)return@runOnUiThread
    dialog.dismiss()
    bookOpenDialog=null
    bookOpenTask=null
    result.onSuccess { (uri,pages) ->
     startActivity(Intent(this,StandaloneReaderActivity::class.java).apply {
      data=uri;putExtra("format",book.format)
      putExtra("bookFileSize",book.sizeBytes)
      if(book.source.type=="bookoasis"&&book.format in setOf("zip","cbz")&&
        uri.scheme=="content"&&uri.pathSegments.firstOrNull()=="book"&&pages>0) {
       putExtra("streamComicSourceId",book.source.id)
       putExtra("streamComicBookId",book.bookId)
       putExtra("streamComicPageCount",pages)
      }
      putExtra("documentId",book.source.id+":"+book.path)
      putExtra("libraryBookId",book.id)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
     })
     repository.recordRecentlyOpened(book,openedFolder)
    }.onFailure {error ->
     android.util.Log.w("ckReader","Book open failed",error)
     if(error !is java.util.concurrent.CancellationException &&
        error !is java.io.InterruptedIOException)
      message("도서 열기 실패: "+(error.message?:"원격 파일 처리 오류"))
    }
   }
  }
 }
 private fun confirmDelete(book:LibraryBook) {
  AlertDialog.Builder(this).setTitle("도서 삭제")
   .setMessage(book.filename+" 파일을 저장소에서 삭제할까요? 이 작업은 되돌릴 수 없습니다.")
   .setNegativeButton("취소",null)
   .setPositiveButton("삭제"){_,_ ->
    operations.submit {
     val result=runCatching {repository.delete(book)}
     runOnUiThread {
      if(!isDestroyed)result.onSuccess {refresh()}
       .onFailure {message(it.message ?: "삭제 실패")}
     }
    }
   }.show()
 }

 private fun scrollContent(main:LinearLayout):LinearLayout {
  val scroll=ScrollView(this).apply {isFillViewport=false}
  val column=vertical().apply {setPadding(0,dp(8),0,dp(28))}
  scroll.addView(column)
  main.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  return column
 }
 private fun showBookDetail(book:LibraryBook,folderOverride:String?=null) {
  saveLibraryAnchor()
  detailTask?.cancel(true)
  detailBook=book
  detailSeriesPath=folderOverride ?: currentFolder
  detailLoading=true
  ++detailLoadVersion
  val version=detailLoadVersion
  val seriesPath=detailSeriesPath
  page=Page.BOOK_DETAIL
  render()
  // Never read remote YAML, a Google Drive file or a web API on the UI thread.
  // Show cached data immediately, then update fields before cover extraction.
  detailTask=detailWorker.submit {
   runCatching {repository.prepareBookDetail(book,seriesPath)}
    .onFailure{android.util.Log.w("ckReader","Detail source metadata lookup failed",it)}
   runOnUiThread {
    if(!isDestroyed&&page==Page.BOOK_DETAIL&&detailBook?.id==book.id&&
      detailLoadVersion==version)render()
   }
   if(Thread.currentThread().isInterrupted)return@submit
   val found=runCatching{repository.detailCover(book).also{
    repository.refreshBookOasisSeriesThumbnail(book,seriesPath)
   }}.getOrElse {
    android.util.Log.w("ckReader","Book detail cover search failed",it)
    false
   }
   runOnUiThread {
    if(isDestroyed||page!=Page.BOOK_DETAIL||detailBook?.id!=book.id||
      detailLoadVersion!=version)return@runOnUiThread
    detailLoading=false
    coverLoader.invalidate(CatalogCoverKey.book(book.source.id,book.id,book.path))
    render()
    if(!found && !repository.hasCachedBookCover(book))
     message("알라딘 API·웹 검색·도서 내부에서 표지를 확인하지 못했습니다.")
   }
  }
 }

 private fun renderBookDetail(main:LinearLayout) {
  val book=detailBook?:run {main.addView(label("도서를 선택하세요."));return}
  val wrapper=scrollContent(main)
  val code=MetadataSourceBadge.code(book.metadataSource)
  val knownSource=code in listOf("K","Y","A","N","L","B")
  val provenance=horizontal().apply {
   tag="metadata-source-detail"
   background=shape(secondaryTint(),if(knownSource)accent() else line(),15f)
   setPadding(dp(12),dp(10),dp(12),dp(10))
   contentDescription="도서정보 출처 "+MetadataSourceBadge.label(code)
  }
  provenance.addView(TextView(this).apply {
   text=if(knownSource)code else if(code=="W")"i" else "?"
   tag="metadata-source-detail-code:"+if(knownSource)code else if(code=="W")"i" else "?"
   gravity=Gravity.CENTER
   textSize=30f
   typeface=Typeface.create("sans-serif",Typeface.BOLD)
   setTextColor(if(knownSource)palette().brandText() else muted())
   background=shape(if(knownSource)accent() else raised(),
    if(knownSource)accent() else line(),12f)
  },LinearLayout.LayoutParams(dp(58),dp(58)))
  val provenanceText=vertical().apply{setPadding(dp(12),0,0,0)}
  provenanceText.addView(label("도서정보 출처",12f,true))
  provenanceText.addView(label(MetadataSourceBadge.label(code),18f).apply {
   typeface=Typeface.create("sans-serif",Typeface.BOLD)
  })
  if(book.metadataSource.isNotBlank())provenanceText.addView(label(book.metadataSource,11f,true))
  provenance.addView(provenanceText,LinearLayout.LayoutParams(0,-2,1f))
  wrapper.addView(provenance,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(16)})
  val cover=ImageView(this).apply{
   setImageResource(R.drawable.ic_book)
   imageTintList=ColorStateList.valueOf(muted())
   scaleType=ImageView.ScaleType.FIT_CENTER
   background=shape(surface(),line(),14f)
  }
  wrapper.addView(cover,LinearLayout.LayoutParams(dp(120),dp(142)).apply{
   gravity=Gravity.CENTER_HORIZONTAL
  })
  loadCover(book,cover,detail=true)
  if(detailLoading)wrapper.addView(label(
   "YAML 도서정보 · 알라딘 API · 웹 검색 · 내부 표지를 확인하는 중입니다.",12f,true))
  wrapper.addView(label(book.shownTitle,21f))
  val fields=listOf(
   "시리즈명" to book.seriesName,
   "시리즈 총권수" to book.seriesTotal.takeIf{it>0}?.toString().orEmpty(),
   "시리즈 내 순번" to book.seriesIndex.takeIf{it>0}?.toString().orEmpty(),
   "제목" to book.title,
   "저자" to book.author,
   "번역자" to book.translator,
   "출판사" to book.publisher,
   "파일포맷" to book.format.uppercase(Locale.ROOT),
   "파일크기" to BookSizeFormatter.format(book.sizeBytes),
   "장르" to book.genre,
   "파일명" to book.filename,
   "출간일(판매일)" to book.publishedDate,
   "도서ID(ISBN)" to book.isbn.ifBlank{book.bookId},
   "가격" to book.price.takeIf{it.isNotBlank()}?.let{it+"원"}.orEmpty(),
   "정보 출처" to book.metadataSource,
   "도서소개" to book.summary
  )
  fields.filter{it.second.isNotBlank()}.forEach{(name,value)->
   wrapper.addView(label(name,12f,true))
   wrapper.addView(label(value,15f))
   wrapper.addView(divider())
  }
  if(book.metadataUrl.startsWith("https://"))wrapper.addView(action("원문 출처 열기"){
   runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(book.metadataUrl)))}
  })
  val actions=horizontal()
  actions.addView(action("읽기",true){openBook(book)},
   LinearLayout.LayoutParams(0,dp(48),1f))
  actions.addView(action("도서정보 검색"){showCandidateSelection(book)},
   LinearLayout.LayoutParams(0,dp(48),1f))
  wrapper.addView(actions)
  // Folder YAML documents are read-only, and available regardless of the
  // metadata enrichment flag or the file's presence in the cached book model.
  if(FolderYamlMetadata.supports(book.source.type)) {
   wrapper.addView(action("YAML · 폴더 도서정보 보기") {
    inspectBookYaml(book)
   })
  }
  if(book.source.type!="bookoasis"&&!book.source.readOnly&&
   !DriveAccess.google(book.source))wrapper.addView(action("도서 삭제"){confirmDelete(book)})
 }

 /** Folder context menu: network lookup is never performed on the UI thread. */
 private fun inspectFolderYaml(source:LibrarySource,folder:String) {
  if(!FolderYamlMetadata.supports(source.type)) {
   message(if(source.type=="bookoasis")
    "북오아시스 API는 시리즈 안의 YAML 원본 파일 목록을 제공하지 않습니다."
    else "이 저장소는 YAML 원본 파일을 탐색할 수 없습니다.")
   return
  }
  operations.submit {
   val found=runCatching {
    val storage=FolderYamlStorage(applicationContext,repository)
    storage.list(source,folder).filter {item->
     !item.directory && YamlReadableDocument.isKavita(item.name) &&
      FolderYamlMetadata.readableSize(item.sizeBytes)
    }
   }
   runOnUiThread {
    if(isDestroyed)return@runOnUiThread
    found.onSuccess {items->
     if(items.isEmpty()) {
      message("이 폴더에는 kavita.yaml 파일이 없습니다.")
     } else {
      AlertDialog.Builder(this).setTitle("폴더 · YAML")
       .setItems(items.map{it.name+" 보기"}.toTypedArray()){_,index->
        openYamlFile(source,items[index])
       }.setNegativeButton("닫기",null).show()
     }
    }.onFailure {message("폴더 YAML 확인 실패: "+(it.message?:"읽기 오류"))}
   }
  }
 }

 /** Details button: Kavita first, then same-stem and other YAML/YML siblings. */
 private fun inspectBookYaml(book:LibraryBook) {
  val folder=book.path.substringBeforeLast('/',"")
  val source=book.source
  operations.submit {
   val found=runCatching {
    val storage=FolderYamlStorage(applicationContext,repository)
    val items=storage.list(source,folder)
    val yaml=items.filter{!it.directory&&YamlReadableDocument.isYamlFile(it.name)
     &&FolderYamlMetadata.readableSize(it.sizeBytes)}
    val bookCount=items.count{!it.directory&&
     listOf(".epub",".pdf",".txt",".md",".zip",".cbz").any{ext->it.name.endsWith(ext,true)}}
    val priority=FolderYamlMetadata.candidates(yaml.map{it.name},book.filename,bookCount)
     .map{it.filename.lowercase(Locale.ROOT)}
    yaml.sortedWith(compareBy<FolderYamlStorage.Entry>{
     val index=priority.indexOf(it.name.lowercase(Locale.ROOT))
     if(index>=0)index else 1000
    }.thenBy{it.name.lowercase(Locale.ROOT)})
   }
   runOnUiThread {
    if(isDestroyed)return@runOnUiThread
    found.onSuccess{files->
     when(files.size){
      0->message("이 도서의 폴더에 YAML 파일이 없습니다.")
      1->openYamlFile(source,files[0])
      else->AlertDialog.Builder(this).setTitle("표시할 YAML 파일 선택")
       .setItems(files.map{it.name}.toTypedArray()){_,index->openYamlFile(source,files[index])}
       .setNegativeButton("취소",null).show()
     }
    }.onFailure{message("YAML 목록을 불러오지 못했습니다: "+(it.message?:"네트워크 오류"))}
   }
  }
 }

 private fun openYamlFile(source:LibrarySource,item:FolderYamlStorage.Entry) {
  if(!YamlReadableDocument.isYamlFile(item.name))return
  val waiting=AlertDialog.Builder(this).setTitle(item.name)
   .setMessage("YAML을 안전하게 읽어 보기 좋은 형태로 변환하는 중입니다.")
   .setNegativeButton("취소",null).create()
  waiting.show()
  operations.submit {
   val result=runCatching {
    val bytes=FolderYamlStorage(applicationContext,repository).read(source,item)
    YamlReadableDocument.parse(bytes)
   }
   runOnUiThread {
    waiting.dismiss()
    if(isDestroyed)return@runOnUiThread
    result.onSuccess {rows->showReadableYaml(item.name,rows)}
     .onFailure{message("YAML 읽기 실패: "+(it.message?:"파일 형식 오류"))}
   }
  }
 }

 private fun showReadableYaml(filename:String,rows:List<YamlReadableDocument.Row>) {
  val outer=ScrollView(this).apply{isFillViewport=false}
  val body=vertical().apply {setPadding(dp(13),dp(7),dp(13),dp(20))}
  body.addView(label("읽기 전용 · 원본 파일은 수정하지 않습니다.",12f,true))
  for(field in rows) {
   val inset=dp((field.depth.coerceIn(0,4))*9)
   val container=vertical().apply{
    setPadding(dp(8)+inset,dp(5),dp(8),dp(7))
    background=shape(surface(),line(),9f)
   }
   when(field) {
    is YamlReadableDocument.Row.Heading->{
     container.addView(label(field.label,15f).apply{
      typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
      setTextColor(accent())
     })
    }
    is YamlReadableDocument.Row.Value->{
     container.addView(label(field.label,12f,true))
     container.addView(label(field.text,15f).apply{
      setTextIsSelectable(true)
      setPadding(0,dp(3),0,dp(3))
     })
    }
    is YamlReadableDocument.Row.Image->{
     container.addView(label(field.label,13f,true))
     val art=ImageView(this).apply {
      setImageBitmap(field.bitmap)
      imageTintList=null
      scaleType=ImageView.ScaleType.FIT_CENTER
      adjustViewBounds=true
      setBackgroundColor(Color.WHITE)
      contentDescription="${field.label} Base64 표지 이미지"
      setOnClickListener {showYamlImageZoom(field.label,field.bitmap)}
     }
     container.addView(art,LinearLayout.LayoutParams(-1,dp(260)))
     container.addView(label("이미지를 누르면 확대·축소할 수 있습니다.",12f,true))
    }
   }
   body.addView(container,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
  }
  outer.addView(body)
  val dlg=AlertDialog.Builder(this).setTitle(filename).setView(outer)
   .setPositiveButton("닫기",null).create()
  dlg.show()
  dlg.window?.setLayout((resources.displayMetrics.widthPixels-dp(12)).coerceAtLeast(dp(270)),
   (resources.displayMetrics.heightPixels*.86f).toInt())
 }

 private fun showYamlImageZoom(name:String,bitmap:android.graphics.Bitmap) {
  val image=ZoomablePreviewView(this,bitmap)
  val dlg=AlertDialog.Builder(this).setTitle(name).setView(image)
   .setNeutralButton("초기화",null).setPositiveButton("닫기",null).create()
  dlg.setOnShowListener {
   dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener{image.reset()}
  }
  dlg.show()
  dlg.window?.setLayout(resources.displayMetrics.widthPixels-dp(16),
   (resources.displayMetrics.heightPixels*.74f).toInt())
 }

 private fun showCandidateSelection(book:LibraryBook) {
  val waiting=AlertDialog.Builder(this).setTitle("도서정보 검색")
   .setMessage("등록된 제공처에서 유사한 책을 찾는 중입니다.")
   .setNegativeButton("취소",null).create()
  waiting.show()
  operations.submit{
   val result=runCatching{repository.metadataSearchResults(book)}
   runOnUiThread{
    waiting.dismiss()
    if(isDestroyed)return@runOnUiThread
    result.onSuccess{candidates->
     if(candidates.isEmpty()){message("일치하는 후보가 없습니다. API 키 및 연결 상태를 확인하세요.");return@onSuccess}
     val query=MetadataTitleMatcher.lookupTitle(book.title,book.filename)
     val candidatesAdapter=object:android.widget.ArrayAdapter<WebBookMetadata>(
      this,android.R.layout.simple_list_item_1,candidates) {
      override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
       val item=getItem(position)!!
       val score=MetadataTitleMatcher.score(query,book.author,book.isbn,item)
       return horizontal().apply {
        setPadding(dp(12),dp(9),dp(12),dp(9))
        gravity=Gravity.CENTER_VERTICAL
        val marker=MetadataSourceBadge.code(item.source)
        addView(TextView(this@LibraryActivity).apply {
         text=marker
         tag="metadata-source:"+marker
         textSize=19f
         gravity=Gravity.CENTER
         setTextColor(accent())
         typeface=Typeface.create("sans-serif",Typeface.BOLD)
         contentDescription="도서정보 출처 "+when(marker){
          "K"->"kavita.yaml";"Y"->"파일명 YAML";"A"->"알라딘 OpenAPI"
          "N"->"국립중앙도서관 OpenAPI";"L"->"도서관 정보마루 OpenAPI"
          "W"->"웹 검색";else->"확인되지 않음"
         }
         background=shape(secondaryTint(),line(),10f)
        },LinearLayout.LayoutParams(dp(39),dp(39)).apply{marginEnd=dp(12)})
        addView(vertical().apply {
         addView(label(item.title+" · "+item.author.ifBlank{"저자 미확인"},15f))
         addView(label(item.source+" · 일치도 "+score+"%"+
          (if(item.publisher.isNotBlank())" · "+item.publisher else ""),12f,true))
        },LinearLayout.LayoutParams(0,-2,1f))
       }
      }
     }
     AlertDialog.Builder(this).setTitle("도서정보 선택 ("+candidates.size+"건)")
      .setAdapter(candidatesAdapter){_,index->
       val selected=candidates[index]
       operations.submit{
        val saved=runCatching {
         if(MetadataSourceBadge.isYaml(selected.source))repository.saveSelectedYamlMetadata(book,selected)
         else repository.saveSelectedMetadata(book,selected)
        }
        runOnUiThread {
         if(isDestroyed)return@runOnUiThread
         saved.onSuccess{
          message("선택한 정보를 캐시 DB에 저장했습니다.")
          if(page==Page.BOOK_DETAIL)render()
          else if(page==Page.LIBRARY) {
           selectedSource()?.let{source->
            repository.cached(source,currentFolder,bookOasisSearch)?.let{listing->
             showListing(source,listing,true,false)
            }
           }
          }
         }.onFailure{message("도서정보 저장 실패: "+(it.message?:"캐시 오류"))}
        }
       }
      }.setNegativeButton("취소",null).show()
    }.onFailure{message("검색 실패: "+(it.message?:"네트워크 오류"))}
   }
  }
 }

 private fun renderGlobalSearch(main:LinearLayout) {
  val input=EditText(this).apply {
   hint="도서명, 저자, 시리즈, 파일명 검색"
   setSingleLine(true);textSize=15f
   setTextColor(ink());setHintTextColor(muted())
   setText(searchText)
   imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
  }
  main.addView(input,LinearLayout.LayoutParams(-1,dp(50)))
  val sources=repository.sources()
  val sourceOptions=listOf("전체 저장소")+sources.map{it.name+" ("+DriveFolderPickerPolicy.storageKind(it)+")"}
  val sourceSelect=Spinner(this)
  val sourceAdapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,sourceOptions)
  sourceSelect.adapter=sourceAdapter
  sourceSelect.setSelection(sources.indexOfFirst{it.id==searchSourceId}.let{if(it<0)0 else it+1})
  val depthSelect=Spinner(this)
  var depths=listOf("전체 1depth")
  fun refreshDepths() {
   val current=sourceSelect.selectedItemPosition
   val chosen=if(current==0)sources else listOfNotNull(sources.getOrNull(current-1))
   val found=chosen.flatMap(repository::cachedDepths).distinct().sorted()
   depths=listOf("전체 1depth")+found
   depthSelect.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,depths)
   val position=depths.indexOf(searchDepth).takeIf{it>=0}?:0
   depthSelect.setSelection(position)
  }
  sourceSelect.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
   override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,pos:Int,id:Long){
    searchSourceId=sources.getOrNull(pos-1)?.id.orEmpty()
    refreshDepths()
   }
   override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
  }
  refreshDepths()
  val filters=horizontal()
  filters.addView(sourceSelect,LinearLayout.LayoutParams(0,dp(54),1f))
  filters.addView(depthSelect,LinearLayout.LayoutParams(0,dp(54),1f))
  main.addView(filters)
  status=label(if(searchBusy)"SQLite 캐시 검색 중..." else
   "검색 대상: 저장된 캐시 DB · 결과 "+searchResults.size+"권",12f,true)
  main.addView(status)
  fun search() {
   val query=input.text.toString().trim().take(120)
   if(query.isBlank()){message("검색어를 입력하세요.");return}
   searchText=query
   searchDepth=depths.getOrNull(depthSelect.selectedItemPosition)?.takeUnless{
    it=="전체 1depth"}.orEmpty()
   val id=searchSourceId
   searchBusy=true;status.text="캐시 DB를 검색하는 중..."
   operations.submit{
    val chosen=if(id.isBlank())repository.sources() else repository.sources().filter{it.id==id}
    val result=runCatching{repository.searchCached(query,chosen,searchDepth.ifBlank{null})}
    runOnUiThread{
     if(isDestroyed||page!=Page.SEARCH)return@runOnUiThread
     searchBusy=false
     result.onSuccess{books->
      searchResults=books
      status.text="캐시 DB 검색 결과 "+books.size+"권"
      adapter.submit(if(books.isEmpty())listOf(Entry.Notice("캐시에 일치하는 도서가 없습니다."))
       else books.mapIndexed{index,book->Entry.Book(book,index+1)})
     }.onFailure{status.text="검색 실패: "+(it.message?:"DB 오류")}
    }
   }
  }
  input.setOnEditorActionListener{_,actionId,_->
   if(actionId==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){search();true}else false
  }
  main.addView(action("캐시에서 검색",true){search()})
  installCatalog(main,if(searchResults.isEmpty())
   listOf(Entry.Notice("검색어를 입력하면 저장소의 캐시 DB에서만 검색합니다."))
   else searchResults.mapIndexed{index,book->Entry.Book(book,index+1)})
 }

 private fun cacheDepthOptions(source:LibrarySource):List<Pair<String,String>> {
  rebuildDepths[source.id]?.let{return it}
  val cached=repository.cached(source,"")
  val folders=cached?.folders.orEmpty()
  val cachedNames=repository.cachedDepths(source)
  val items=LinkedHashMap<String,String>()
  items["__root__"]="저장소 전체"
  if(source.type=="local"){
   cached?.books.orEmpty().forEach{book->
    val depth=book.path.substringBefore('/',"__root__").let{
     if('/' !in book.path)"__root__" else it
    }
    items[depth]=if(depth=="__root__")"루트" else depth
   }
  } else folders.forEach{
   items[it]=if(source.type=="bookoasis")BookOasisPaths.display(it) else it
  }
  cachedNames.forEach{if(it!="root")items.putIfAbsent(it,it)}
  return items.map{it.key to it.value}.also{rebuildDepths[source.id]=it}
 }
 private fun loadCacheDepthOptions(source:LibrarySource) {
  cacheUsageRequested=false
  if(!rebuildBusy.add(source.id))return
  operations.submit {
   val result=runCatching {
    val listing=repository.browse(source,"")
    rebuildDepths.remove(source.id)
    if(source.type=="local") {
     val all=LinkedHashMap<String,String>()
     all["__root__"]="저장소 전체"
     listing.books.forEach{
      val folder=if('/' in it.path)it.path.substringBefore('/') else "__root__"
      all[folder]=if(folder=="__root__")"루트" else folder
     }
     all.map{it.key to it.value}
    } else listOf("__root__" to "저장소 전체")+
     listing.folders.map{it to if(source.type=="bookoasis")
      BookOasisPaths.display(it) else it}
   }
   runOnUiThread {
    rebuildBusy.remove(source.id)
    if(isDestroyed)return@runOnUiThread
    result.onSuccess{rebuildDepths[source.id]=it.distinctBy(Pair<String,String>::first)
     if(page==Page.CACHE_REBUILD)render()
    }.onFailure{message("1depth 조회 실패: "+(it.message?:"네트워크 오류"))}
   }
  }
 }
 private fun startCacheRebuild() {
  if(rebuildSelections.isEmpty()){message("저장소 또는 1depth를 선택하세요.");return}
  if(CacheRebuildService.running) {
   AlertDialog.Builder(this).setTitle("실행 중인 캐시 생성 작업")
    .setMessage("이미 실행 중인 작업이 있습니다. 새 작업을 대기열에 추가하거나 기존 작업을 취소하고 교체할 수 있습니다.")
    .setItems(arrayOf("기존 작업 완료 후 대기 실행",
     "기존 작업 취소 후 새 작업 시작")){_,choice->
      requestCacheRebuild(if(choice==0)CacheRebuildService.StartMode.WAIT
       else CacheRebuildService.StartMode.REPLACE)
    }.setNegativeButton("돌아가기",null).show()
   return
  }
  requestCacheRebuild(CacheRebuildService.StartMode.WAIT)
 }
 private fun requestCacheRebuild(mode:CacheRebuildService.StartMode) {
  waitingSelections=rebuildSelections.toList()
  waitingMode=mode
  waitingForceRebuild=cacheRebuildForce
  if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
   !=PackageManager.PERMISSION_GRANTED){
   requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),notifyPermissionRequest)
   return
  }
  startCacheRebuildNow()
 }
 private fun cacheSizeText(source:LibrarySource,depth:String,title:String):String {
  val snapshot=rebuildUsage[source.id]
  val size=if(snapshot!=null)CacheUsage.format(repository.cacheUsageForDepth(source,snapshot,depth))
   else if(source.id in rebuildUsageFailed)"용량 확인 실패" else "용량 계산 중…"
  return title+" · "+size
 }
 private fun bindCacheSize(source:LibrarySource,depth:String,title:String,view:TextView) {
  rebuildSizeLabels.add(CacheSizeLabel(source,depth,title,view))
  view.text=cacheSizeText(source,depth,title)
 }
 private fun loadCacheUsage() {
  if(cacheUsageBusy||isDestroyed)return
  cacheUsageBusy=true;cacheUsageRequested=true
  val sources=repository.sources()
  cacheUsageWorker.submit {
   val snapshots=sources.associate{it.id to runCatching{repository.cacheUsage(it)}}
   runOnUiThread {
    cacheUsageBusy=false
    if(isDestroyed)return@runOnUiThread
    snapshots.forEach{(id,result)->
     result.onSuccess{rebuildUsage[id]=it;rebuildUsageFailed.remove(id)}
      .onFailure{rebuildUsage.remove(id);rebuildUsageFailed.add(id)}
    }
    if(page==Page.CACHE_REBUILD)rebuildSizeLabels.forEach{
     it.view.text=cacheSizeText(it.source,it.depth,it.title)
    }
   }
  }
 }
 private fun startCacheRebuildNow(){
  val requests=waitingSelections
  waitingSelections=emptyList()
  val mode=waitingMode
  val force=waitingForceRebuild
  waitingMode=CacheRebuildService.StartMode.WAIT
  waitingForceRebuild=false
  runCatching{CacheRebuildService.start(applicationContext,requests,mode,force)}
   .onSuccess{
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    message(if(mode==CacheRebuildService.StartMode.REPLACE)
     "이전 작업 취소를 요청했습니다. 새 캐시 작업을 시작합니다."
     else "캐시 생성 작업을 등록했습니다. 알림에서 취소할 수 있습니다.")
    refreshCacheProgress()
   }.onFailure{message("캐시 작업 시작 실패: "+(it.message?:"서비스 오류"))}
 }
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,
                                         grantResults:IntArray){
  super.onRequestPermissionsResult(requestCode,permissions,grantResults)
  if(requestCode==notifyPermissionRequest) {
   if(grantResults.firstOrNull()!=PackageManager.PERMISSION_GRANTED)
    message("알림 권한이 없어 완료 알림이 보이지 않을 수 있습니다.")
   startCacheRebuildNow()
  }
 }
 private fun addCacheProgressPanel(content:LinearLayout) {
  content.addView(section("CACHE STATUS  /  캐시생성 작업 진행상태"))
  val summary=label("",14f,true)
  val current=label("",13f)
  current.setTextIsSelectable(true)
  content.addView(summary)
  content.addView(current)
  val cancel=action("진행 중인 작업과 대기 작업 모두 취소"){
   AlertDialog.Builder(this).setTitle("캐시 생성 취소")
    .setMessage("현재 진행 중인 작업과 대기열에 등록된 모든 작업을 취소할까요? 이미 처리 중인 파일은 안전하게 마무리한 뒤 중단됩니다.")
    .setNegativeButton("돌아가기",null)
    .setPositiveButton("작업 취소"){_,_->
     runCatching{CacheRebuildService.cancel(applicationContext)}
      .onSuccess{message("캐시 생성 취소를 요청했습니다.")}
      .onFailure{message("취소 요청 실패: "+(it.message?:"서비스 오류"))}
    }.show()
  }
  content.addView(cancel,cacheRebuildButtonParams())
  val history=vertical()
  val toggle=action(if(showCacheProgressHistory)"최근 50개 접기" else "최근 50개 펼치기"){}
  toggle.setOnClickListener{
   showCacheProgressHistory=!showCacheProgressHistory
   history.visibility=if(showCacheProgressHistory)View.VISIBLE else View.GONE
   toggle.text=if(showCacheProgressHistory)"최근 50개 접기" else "최근 50개 펼치기"
  }
  content.addView(toggle,cacheRebuildButtonParams())
  history.visibility=if(showCacheProgressHistory)View.VISIBLE else View.GONE
  content.addView(history)
  cacheProgressSummary=summary
  cacheProgressCurrent=current
  cacheProgressCancel=cancel
  cacheProgressHistory=history
  cacheProgressHistoryMarker=null
  refreshCacheProgress()
 }
 private fun refreshCacheProgress() {
  val summary=cacheProgressSummary?:return
  val current=cacheProgressCurrent?:return
  val history=cacheProgressHistory?:return
  if(page!=Page.SETTINGS&&page!=Page.CACHE_REBUILD)return
  val snapshot=cacheProgressStore.read()
  cacheProgressCancel?.visibility=if(CacheRebuildService.running)View.VISIBLE else View.GONE
  val phase=when(snapshot.phase) {
   "running"->"진행 중"
   "waiting"->"시작 대기"
   "cancelling"->"취소 요청 처리 중"
   "cancelled"->"취소됨"
   "completed"->"완료"
   else->"대기 중"
  }
  summary.text="상태: "+phase+" · 처리 "+snapshot.processed+"권 (성공 "+
   snapshot.success+" / 실패 "+snapshot.failure+") · 건너뜀 "+
   snapshot.skipped+"권 · 대기 "+snapshot.waiting+"건"
  current.text=when {
   snapshot.file.isNotBlank()->"현재 파일: "+snapshot.file+"\n저장소: "+
    snapshot.storage+"\n경로: "+snapshot.path
   snapshot.storage.isNotBlank()->"탐색 중인 저장소: "+snapshot.storage+
    "\n경로: "+snapshot.path
   snapshot.phase=="running"||snapshot.phase=="cancelling"->
    "저장소 또는 파일 검색을 진행 중입니다."
   else->"현재 처리 중인 파일이 없습니다."
  }
  val marker=snapshot.recent.hashCode()
  if(cacheProgressHistoryMarker==marker)return
  cacheProgressHistoryMarker=marker
  history.removeAllViews()
  history.addView(label("최근 처리 파일 "+snapshot.recent.size+"개 / 최대 50개",12f,true))
  if(snapshot.recent.isEmpty()) {
   history.addView(label("아직 처리된 파일이 없습니다.",12f,true))
   return
  }
  val formatter=SimpleDateFormat("MM-dd HH:mm:ss",Locale.KOREA)
  snapshot.recent.forEachIndexed{index,item->
   val date=formatter.format(Date(item.timestamp))
   val outcome=if(item.skipped)"기존 캐시 · 건너뜀" else if(item.success)"성공" else "실패"
   history.addView(label((index+1).toString()+". ["+outcome+"] "+item.file+
    "\n"+item.storage+" / "+item.path+" · "+date,12f).apply {
     setTextIsSelectable(true)
    })
   if(index<snapshot.recent.lastIndex)history.addView(divider(true))
  }
 }

 private fun cacheRebuildButtonParams()=LinearLayout.LayoutParams(-1,-2).apply {
  bottomMargin=dp(10)
 }

 private fun renderCacheRebuild(main:LinearLayout) {
  rebuildSizeLabels.clear()
  val panel=scrollContent(main)
  panel.addView(section("STORAGES / 1DEPTH · 다중 선택"))
  val selectedCount=label("선택한 폴더 "+rebuildSelections.size+"개",13f,true)
  panel.addView(selectedCount)
  panel.addView(label("현재 캐시 용량 · 1 MB 미만은 1 MB로 표시",12f,true))
  panel.addView(action("캐시 용량 새로고침"){loadCacheUsage()},cacheRebuildButtonParams())
  repository.sources().forEach{source->
   val details=vertical()
   val choices=cacheDepthOptions(source)
   fun headerText():String {
    val count=rebuildSelections.count{it.sourceId==source.id}
    return (if(source.id in expandedRebuildSources)"▾ " else "▸ ")+
     source.name+" · 선택 "+count+"개"
   }
   val expander=action(headerText()){}
   // Update the button text after toggling without reconstructing the page.
   expander.setOnClickListener {
    if(!expandedRebuildSources.add(source.id))expandedRebuildSources.remove(source.id)
    details.visibility=if(source.id in expandedRebuildSources)View.VISIBLE else View.GONE
    expander.text=headerText()
   }
   panel.addView(expander,cacheRebuildButtonParams())
   details.visibility=if(source.id in expandedRebuildSources)View.VISIBLE else View.GONE
   details.addView(action(if(source.id in rebuildBusy)"조회 중..." else "1depth 다시 조회"){
    loadCacheDepthOptions(source)
   },cacheRebuildButtonParams())
   val group=CheckBox(this).apply {
    setTextColor(ink())
    isChecked=choices.all{CacheRebuildService.Selection(source.id,it.first) in rebuildSelections}
   }
   bindCacheSize(source,"__root__","저장소 전체 선택",group)
   details.addView(group)
   val children=ArrayList<CheckBox>()
   choices.forEach{(depth,title)->
    val value=CacheRebuildService.Selection(source.id,depth)
    val child=CheckBox(this).apply{
     setTextColor(ink())
     isChecked=value in rebuildSelections
     setPadding(dp(20),0,0,0)
     setOnCheckedChangeListener{_,checked->
      if(checked)rebuildSelections.add(value) else rebuildSelections.remove(value)
      selectedCount.text="선택한 폴더 "+rebuildSelections.size+"개"
      expander.text=headerText()
     }
    }
    bindCacheSize(source,depth,title,child)
    children.add(child)
    details.addView(child)
   }
   group.setOnCheckedChangeListener{_,checked->
    children.forEach{it.isChecked=checked}
   }
   panel.addView(details)
   panel.addView(divider(true))
  }
  val forceOption=CheckBox(this).apply {
   text="강제 재생성 (캐시가 있는 도서도 다시 처리)"
   setTextColor(ink())
   isChecked=cacheRebuildForce
   setOnCheckedChangeListener{_,checked->cacheRebuildForce=checked}
  }
  panel.addView(forceOption)
  panel.addView(label("기본값: 이미 저장된 표지 또는 도서정보가 있으면 건너뜁니다. "+
    "단, Google Drive에서 YAML이 없는 도서는 폴더 정리를 위해 다시 처리합니다. "+
    "강제 재생성 시에는 기존 도서도 다시 처리합니다.",12f,true))
  panel.addView(action("선택 항목 캐시 재생성 시작",true){startCacheRebuild()},cacheRebuildButtonParams())
  addCacheProgressPanel(panel)
  if(!cacheUsageRequested)loadCacheUsage()
 }

 private fun configureAladinApiKey(provider:MetadataApiProvider=MetadataApiProvider.ALADIN) {
  val vault=AladinApiCredentials(this,provider)
  val keyField=EditText(this).apply {
   hint=if(provider==MetadataApiProvider.ALADIN)"X-API-Key 입력" else "발급받은 인증키 입력"
   inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
   setSingleLine(true)
   setTextColor(ink())
   setHintTextColor(muted())
   setSelectAllOnFocus(true)
  }
  val container=LinearLayout(this).apply {
   orientation=LinearLayout.VERTICAL
   setPadding(dp(22),dp(8),dp(22),0)
   addView(keyField,LinearLayout.LayoutParams(-1,dp(55)))
  }
  AlertDialog.Builder(this).setTitle(provider.label+" 검색 API Key")
   .setView(container)
   .setPositiveButton("저장"){_,_->
    val key=keyField.text?.toString()?.trim().orEmpty()
    when {
     !AladinApiCredentials.validKey(key)->message("API Key 형식을 확인하세요.")
     vault.save(key)->{
      message("API Key를 암호화 저장했습니다.")
      if(page==Page.SETTINGS)render()
     }
     else->message("API Key 저장에 실패했습니다.")
    }
   }
   .setNeutralButton("키 삭제"){_,_->
    AlertDialog.Builder(this).setTitle(provider.label+" API Key 삭제")
     .setMessage("앱에 저장된 "+provider.label+" 도서 검색 키만 삭제합니다.")
     .setNegativeButton("취소",null)
     .setPositiveButton("삭제"){_,_->
      if(vault.clear()){message("API Key 삭제 완료");if(page==Page.SETTINGS)render()}
      else message("API Key 삭제 실패")
     }.show()
   }
   .setNegativeButton("취소",null).show()
 }
 private fun testAladinApiKey() {
  val vault=AladinApiCredentials(this)
  if(!vault.configured()) {
   message("먼저 알라딘 API Key를 등록하세요.")
   return
  }
  message("알라딘 검색 API 연결 확인 중...")
  operations.submit {
   val result=runCatching{AladinApiGateway(applicationContext).test()}
   runOnUiThread {
    if(isDestroyed)return@runOnUiThread
    result.onSuccess{
     val state=when(it.status) {
      AladinApiStatus.HTTPS_OK->"HTTPS API 인증 및 연결 정상"
      AladinApiStatus.LEGACY_OK->"HTTPS 접속 실패 · 이전 HTTP 서버는 읽기 가능"
      AladinApiStatus.KEY_MISSING->"API Key가 없거나 해독할 수 없습니다."
      AladinApiStatus.AUTH_FAILED->"API Key가 잘못됐거나 만료되었습니다."
      AladinApiStatus.HTTPS_UNAVAILABLE->"HTTPS 연결 실패 · 이전 HTTP 서버에도 연결할 수 없습니다."
      AladinApiStatus.LEGACY_FORBIDDEN->"HTTPS 연결 실패 · 이전 HTTP 서버가 요청을 거부했습니다."
      AladinApiStatus.SERVER_ERROR->"서버 오류 (HTTP "+(it.httpStatus?.toString()?:"미확인")+")"
     }
     AlertDialog.Builder(this).setTitle("알라딘 API 연결 검사")
      .setMessage(state).setPositiveButton("확인",null).show()
    }.onFailure{message("연결 검사 중 오류가 발생했습니다.")}
   }
  }
 }
 private fun testLibraryApiKey(provider:MetadataApiProvider) {
  if(!AladinApiCredentials(this,provider).configured()) {
   message("먼저 "+provider.label+" API Key를 등록하세요.")
   return
  }
  message(provider.label+" API 연결 확인 중...")
  operations.submit {
   val client=PublicLibraryCatalogClient(applicationContext)
   val success=runCatching{client.search(provider,"어린 왕자");client.contacted}.getOrDefault(false)
   runOnUiThread {
    if(!isDestroyed)AlertDialog.Builder(this).setTitle(provider.label+" API 연결 검사")
     .setMessage(if(success)"API 인증 및 응답 정상" else "인증 또는 응답 확인 실패 · 키와 네트워크를 확인하세요.")
     .setPositiveButton("확인",null).show()
   }
  }
 }
 private fun renderSettings(main:LinearLayout) {
  val content=scrollContent(main)
  content.addView(section("APPEARANCE  /  화면"))
  if(GlobalConfig.isEInk(this))
   content.addView(label("E-Ink 화면 · 고대비 흑백 / 애니메이션 최소화",16f,true))
  else content.addView(label("화면 테마",19f))
  val current=GlobalConfig.themeMode(this)
  val themeButtons=horizontal().apply {setPadding(0,dp(8),0,dp(14))}
  (if(GlobalConfig.isEInk(this))emptyList() else listOf("라이트" to "light","다크" to "dark","시스템설정" to "system"))
   .forEach { (title,mode) ->
    themeButtons.addView(action(title,current==mode){
     if(GlobalConfig.themeMode(this)!=mode) {
      GlobalConfig.setThemeMode(this,mode)
      recreate()
     }
    },LinearLayout.LayoutParams(0,dp(48),1f))
   }
  if(!GlobalConfig.isEInk(this))content.addView(themeButtons)

  content.addView(section("FONTS  /  독서 글꼴"))
  content.addView(entryRow(R.drawable.ic_book,"설치된 글꼴 관리",""){
   page=Page.FONTS;render()
  })

  content.addView(section("CACHE  /  도서 캐시"))
  content.addView(entryRow(R.drawable.ic_refresh,"캐시 재생성 · 저장소/1depth 다중선택",""){
   cacheUsageRequested=false
   rebuildDepths.clear()
   expandedRebuildSources.clear()
   page=Page.CACHE_REBUILD;render()
  })
  addCacheProgressPanel(content)
  content.addView(entryRow(R.drawable.ic_folder,"캐시 저장 위치",
   repository.cacheLocationText()){chooseDocumentsCacheFolder()})
  content.addView(entryRow(R.drawable.ic_refresh,"모든 도서 캐시 삭제",""){
   AlertDialog.Builder(this).setTitle("캐시 삭제")
    .setMessage("저장소별 목록, 도서정보 및 표지 캐시 DB를 삭제할까요? 도서 원본과 계정 설정은 유지됩니다.")
    .setNegativeButton("취소",null)
    .setPositiveButton("캐시 삭제"){_,_->
     operations.submit {
      val cleared=runCatching{repository.clearAllPersistentCache()}
      runOnUiThread {
       if(!isDestroyed)cleared.onSuccess{
        coverLoader.clearMemory()
        message("저장된 캐시를 삭제했습니다.")
        if(page==Page.LIBRARY)refresh() else render()
       }.onFailure{message("캐시 삭제 실패: "+(it.message?:"저장소 권한 확인"))}
      }
     }
    }.show()
  })
  content.addView(entryRow(R.drawable.ic_refresh,"열어본 도서의 임시 다운로드 삭제",""){
   AlertDialog.Builder(this).setTitle("임시 다운로드 삭제")
    .setMessage("열어볼 때 다운로드한 도서 파일만 삭제할까요? 원본과 목록·도서정보·표지 캐시는 유지됩니다.")
    .setNegativeButton("취소",null)
    .setPositiveButton("삭제"){_,_->
     operations.submit {
      val deleted=repository.clearDownloadedBookFiles()
      runOnUiThread{if(!isDestroyed)message("임시 도서 파일 ${deleted.first}개 · ${deleted.second/1024/1024}MB 삭제")}
     }
    }.show()
  })

  content.addView(section("STORAGE  /  저장소"))
  if(!BuildConfig.LITE)content.addView(entryRow(R.drawable.ic_storage_drive,"개인 Google OAuth 설정 안내",
   "Google Cloud 프로젝트와 RClone 연결을 단계별로 설정합니다."){
   page=Page.GOOGLE_OAUTH_GUIDE;render()
  })
  content.addView(entryRow(R.drawable.ic_storage_local,"저장소 관리",""){
   page=Page.MANAGE;render()
  })
  content.addView(divider())
  content.addView(entryRow(R.drawable.ic_refresh,"목록 캐시 삭제",""){
   repository.sources().forEach {repository.clearCache(it.id)}
   message("저장된 도서 목록을 삭제했습니다.")
  })

  content.addView(section("BOOK METADATA  /  자동 도서정보"))
  if(!BuildConfig.LITE){
   content.addView(apiKeyRow("A","알라딘 OpenAPI Key",
   if(AladinApiCredentials(this).configured())"등록됨" else "미등록"){
   configureAladinApiKey()
  })
  content.addView(entryRow(R.drawable.ic_refresh,"알라딘 API 연결 테스트",""){
   testAladinApiKey()
  })
  for(provider in listOf(MetadataApiProvider.NATIONAL_LIBRARY,MetadataApiProvider.DATA4LIBRARY)) {
   val code=if(provider==MetadataApiProvider.NATIONAL_LIBRARY)"N" else "L"
   content.addView(apiKeyRow(code,provider.label+" OpenAPI Key",
    if(AladinApiCredentials(this,provider).configured())"등록됨" else "미등록"){
    configureAladinApiKey(provider)
   })
   content.addView(entryRow(R.drawable.ic_refresh,provider.label+" API 연결 테스트",""){
    testLibraryApiKey(provider)
   })
  }
  }
  content.addView(label(if(BuildConfig.LITE)
   "검색 순서: 폴더 YAML → 네이버·카카오·리디 등 공개 웹 정보"
   else "검색 순서: 폴더 YAML → 알라딘 → 국립중앙도서관 → 정보나루 → 네이버·카카오·리디 등",12f).apply {
   setTextColor(muted())
  })
  content.addView(android.widget.Switch(this).apply{
   text="웹에서 도서정보 자동 보완"
   setTextColor(ink());isChecked=repository.metadataEnabled()
   setOnCheckedChangeListener { _,enabled ->
    repository.setMetadataEnabled(enabled)
    message(if(enabled)"도서정보 자동 검색이 활성화되었습니다." else "도서정보 자동 검색을 중지했습니다.")
   }
  })
  content.addView(entryRow(R.drawable.ic_refresh,"웹 도서정보 캐시 초기화",""){
   repository.clearWebMetadata()
   coverLoader.forgetMisses()
   message("웹 도서정보 캐시를 초기화했습니다.")
   if(selectedSource()!=null) {
    page=Page.LIBRARY;render();refresh()
   }
  })

  val installedModules=(listOf(
   "Selvy I AI 음성" to "com.ckchoi.ckreader.voice.yes24",
   "Selvy II AI 음성" to "com.ckchoi.ckreader.voice.selvy",
   "ONNX AI 음성" to "com.ckchoi.ckreader.voice.willa",
   "RClone" to "com.ckchoi.ckreader.rclone"
  )+ReaderFonts.packs.map{it.label to it.packageName})
   .filter{runCatching{packageManager.getPackageInfo(it.second,0)}.isSuccess}
  if(installedModules.isNotEmpty())content.addView(section("MODULES  /  설치된 모듈"))
  installedModules.forEach { (name,pkg) ->
   content.addView(voiceStatusCard(name,pkg))
  }
  content.addView(appInfoFooter())
 }
 private fun appInfoFooter():View {
  val appName=getString(R.string.app_name)
  val version=packageManager.getPackageInfo(packageName,0).versionName.orEmpty()
  return horizontal().apply {
   gravity=Gravity.CENTER
   layoutParams=LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(24)}
   setPadding(dp(12),dp(16),dp(12),dp(16))
   addView(ImageView(this@LibraryActivity).apply {
    setImageResource(R.drawable.ic_launcher)
    scaleType=ImageView.ScaleType.FIT_CENTER
    contentDescription="$appName 앱 아이콘"
   },LinearLayout.LayoutParams(dp(48),dp(48)))
   addView(vertical().apply {
    setPadding(dp(12),0,0,0)
    addView(label(appName,16f))
    addView(label("버전 $version",12f,true))
   })
  }
 }
 private fun voiceStatusCard(name:String,pkg:String):View {
  val installed=runCatching{packageManager.getPackageInfo(pkg,0)}.getOrNull()
  val version=installed?.versionName?.takeIf{it.isNotBlank()}
   ?:installed?.let{"빌드 "+androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)}
  return horizontal().apply {
   tag="voice:$pkg"
   layoutParams=cardLayoutParams()
   minimumHeight=dp(88)
   setPadding(dp(14),dp(14),dp(14),dp(14))
   background=shape(surface(),line(),17f)
   val engineIcon=when(pkg) {
    "com.ckchoi.ckreader.voice.yes24" -> R.drawable.icon_engine_selvy_i
    "com.ckchoi.ckreader.voice.selvy" -> R.drawable.icon_engine_selvy_ii
    "com.ckchoi.ckreader.voice.willa" -> R.drawable.icon_engine_onnx
    "com.ckchoi.ckreader.fonts.font14","com.ckchoi.ckreader.fonts.font7" -> R.drawable.ic_book
    else -> R.drawable.ic_voice
   }
   val addonIcon=if(pkg=="com.ckchoi.ckreader.rclone" ||
    pkg=="com.ckchoi.ckreader.fonts.font14" || pkg=="com.ckchoi.ckreader.fonts.font7")
    runCatching{packageManager.getApplicationIcon(pkg)}.getOrNull()
   else null
   addView(ImageView(this@LibraryActivity).apply {
    if(addonIcon!=null)setImageDrawable(addonIcon) else setImageResource(engineIcon)
    imageTintList=null
    scaleType=ImageView.ScaleType.FIT_CENTER
    contentDescription=name
    background=shape(secondaryTint(),secondaryTint(),11f)
    setPadding(dp(3),dp(3),dp(3),dp(3))
   },LinearLayout.LayoutParams(dp(48),dp(48)))
   addView(vertical().apply {
    setPadding(dp(10),0,dp(8),0)
    addView(label(name,16f).apply {typeface=Typeface.create("sans-serif",Typeface.BOLD)})
    addView(label(if(version!=null)"버전 $version" else "설치된 버전 없음",12f,true))
   },LinearLayout.LayoutParams(0,-2,1f))
   addView(label(if(installed!=null)"설치됨" else "미설치",12f,true).apply {
    setTextColor(if(installed!=null)palette().tintText() else muted())
    background=shape(if(installed!=null)secondaryTint() else paper(),line(),10f)
    setPadding(dp(9),dp(6),dp(9),dp(6))
   })
   if(installed!=null)addView(action("삭제"){requestModuleUninstall(name,pkg)})
  }
 }
 private fun requestModuleUninstall(name:String,pkg:String) {
  if(pkg!="com.ckchoi.ckreader.rclone") {launchModuleUninstaller(name,pkg);return}
  val affected=RcloneModuleRemoval.affectedSources(repository.sources())
  if(affected.isEmpty()){launchModuleUninstaller(name,pkg);return}
  if(CacheRebuildService.running){
   message("캐시 재생성 작업을 취소하거나 완료한 뒤 RClone을 삭제하세요.")
   return
  }
  val ids=affected.map{it.id}.toSet()
  val names=affected.take(8).joinToString("\n"){"• "+it.name}
  val more=if(affected.size>8)"\n외 ${affected.size-8}개" else ""
  AlertDialog.Builder(this).setTitle("RClone 모듈 삭제")
   .setMessage("RClone을 삭제하면 다음 저장소 ${affected.size}개는 사용할 수 없어 연결 정보를 삭제합니다.\n"+
    names+more+"\n\n저장소별 목록·도서정보·표지 캐시와 열어본 도서의 임시 다운로드 파일도 삭제됩니다. 원격 도서 파일은 삭제되지 않습니다.\n\n"+
    "계속하면 저장소 정보와 캐시를 먼저 삭제한 뒤 Android의 모듈 삭제 확인 화면이 열립니다.")
   .setNegativeButton("취소",null)
   .setPositiveButton("저장소와 캐시 삭제 후 계속"){_,_->
    val waiting=AlertDialog.Builder(this).setTitle("RClone 삭제 준비")
     .setMessage("저장소 정보와 캐시를 삭제하는 중입니다.")
     .setCancelable(false).create().apply{show()}
    operations.submit {
     val result=runCatching {
      check(!CacheRebuildService.running){"캐시 재생성 작업을 취소하거나 완료한 뒤 다시 시도하세요."}
      check(RcloneModuleRemoval.affectedSources(repository.sources()).map{it.id}.toSet()==ids){
       "저장소 목록이 변경됐습니다. 다시 확인한 뒤 삭제하세요."
      }
      repository.removeSources(ids,clearDownloads=true)
     }
     runOnUiThread {
      waiting.dismiss()
      if(isDestroyed)return@runOnUiThread
      result.onSuccess {
       if(selectedSourceId in ids){
        selectedSourceId=null;currentFolder=""
        navigation.edit().remove("source_id").remove("folder").apply()
       }
       if(searchSourceId in ids){searchSourceId="";searchResults=emptyList()}
       rebuildSelections.removeAll{it.sourceId in ids}
       ids.forEach{rebuildDepths.remove(it);rebuildUsage.remove(it)}
       coverLoader.clearMemory()
       render()
       launchModuleUninstaller(name,pkg)
      }.onFailure{message("RClone 저장소 또는 캐시 삭제 실패: "+(it.message?:"다시 시도하세요."))}
     }
    }
   }.show()
 }
 private fun launchModuleUninstaller(name:String,pkg:String) {
  val intent=Intent(Intent.ACTION_UNINSTALL_PACKAGE,Uri.parse("package:$pkg"))
   .putExtra(Intent.EXTRA_RETURN_RESULT,true)
  try {
   pendingModuleUninstall=name to pkg
   startActivityForResult(intent,moduleUninstallRequest)
  } catch(error:Exception) {
   pendingModuleUninstall=null
   message("모듈 삭제 화면을 열 수 없습니다: "+(error.message?:"Android 설정을 확인하세요."))
  }
 }
 private fun renderGoogleOAuthGuide(main:LinearLayout) {
  val content=scrollContent(main)
  content.addView(section("GOOGLE DRIVE  /  개인 OAuth 프로젝트"))
  content.addView(label(
   "개인 Google Cloud 프로젝트를 사용하는 방법은 RClone 저장소 연결입니다. "+
    "이 안내로 만든 OAuth 정보는 앱의 'Google Drive' 직접 로그인에는 적용되지 않습니다. "+
    "직접 로그인은 설치된 앱의 패키지명과 서명 인증서에 등록된 Android OAuth 클라이언트를 사용합니다.",14f))
  content.addView(label("준비물: Google 계정, PC의 rclone, 기기에 설치된 ckReader.RClone.apk",12f,true))

  fun step(number:Int,title:String,body:String) {
   content.addView(vertical().apply {
    layoutParams=cardLayoutParams()
    background=shape(surface(),line(),17f)
    setPadding(dp(14),dp(13),dp(14),dp(13))
    addView(label("$number. $title",17f).apply {
     typeface=Typeface.create("sans-serif",Typeface.BOLD)
     setTextColor(accent())
    })
    addView(label(body,14f).apply {
     setLineSpacing(dp(3).toFloat(),1.08f)
     setTextIsSelectable(true)
    })
   })
  }
  step(1,"Google Cloud 프로젝트 만들기",
   "PC 브라우저에서 console.cloud.google.com에 로그인합니다. 상단 프로젝트 선택 → 새 프로젝트 → 이름 입력 → 만들기를 누르고, 만든 프로젝트가 선택되어 있는지 확인하세요.")
  step(2,"Google Drive API 켜기",
   "API 및 서비스 → 라이브러리에서 'Google Drive API'를 찾아 '사용'을 누릅니다. API를 켜는 프로젝트가 1단계에서 만든 프로젝트인지 확인하세요.")
  step(3,"OAuth 동의 화면 설정",
   "Google Auth Platform의 브랜딩에서 앱 이름·지원 이메일을 입력합니다. 대상(Audience)은 개인 Google 계정이면 '외부'를 선택하고, 테스트 사용자에 실제 Drive를 사용할 Google 계정을 추가하세요. 데이터 액세스의 범위도 사용할 권한에 맞춰 설정합니다.")
  step(4,"Drive 권한 고르기",
   "기존 도서 폴더를 정리하고 kavita.yaml을 생성하려면 전체 파일 읽기·쓰기 범위인 'drive'가 필요합니다. 조회만 할 경우 'drive.readonly'를 선택할 수 있습니다. 두 범위 모두 Google의 제한 범위이며, 공개 배포에는 검증 요건이 적용됩니다. 테스트 상태의 승인은 7일 후 만료될 수 있습니다.")
  step(5,"데스크톱 OAuth 클라이언트 생성",
   "Google Auth Platform → 클라이언트 → 클라이언트 만들기에서 애플리케이션 유형을 '데스크톱 앱'으로 선택합니다. 생성된 클라이언트 ID와 클라이언트 보안 비밀을 기록하세요. 이 단계는 PC에서 rclone이 인증할 용도이므로 Android 유형을 선택하지 않습니다.")
  step(6,"PC에서 rclone 인증",
   "PC에 rclone을 설치하고 터미널에서 'rclone config'를 실행합니다. 새 리모트(n) → 이름 입력 → 저장소 유형 'drive' → 5단계의 client_id와 client_secret 입력 → 권한 선택(쓰기: 1 / drive, 읽기 전용: 2 / drive.readonly) → 브라우저에서 Google 계정 승인 순으로 진행하세요. 기존 리모트의 클라이언트를 바꾼 경우 토큰도 다시 승인해야 합니다.")
  step(7,"rclone.conf를 기기로 가져오기",
   "PC에서 'rclone config file'을 실행해 설정 파일 위치를 확인합니다. rclone.conf를 기기의 Download 폴더로 안전하게 복사하세요. ckReader에서 저장소 추가 → RClone → rclone.conf 선택 → 해당 리모트 선택 → '일반' 선택 → 폴더와 저장소 이름 확인 → 저장소 저장 순으로 진행합니다.")
  step(8,"연결과 쓰기 확인",
   "새 저장소의 도서 목록이 보이는지 확인하세요. 폴더 정리와 YAML 생성을 원한다면 4·6단계에서 'drive' 범위를 선택했고 대상 폴더에 쓰기 권한이 있는지 확인하세요. 인증이 만료되면 PC의 rclone에서 다시 승인한 뒤 새 rclone.conf를 가져와 연결을 갱신해야 합니다.")

  content.addView(section("SECURITY  /  연결 정보 보관"))
  content.addView(label("rclone.conf에는 갱신 토큰이 들어 있습니다. 다른 사람에게 보내거나 공개 저장소에 올리지 마세요. 테스트 계정·검증·동의 화면 요구 사항은 Google 정책에 따라 달라질 수 있습니다.",13f))
  content.addView(section("OFFICIAL GUIDES  /  공식 안내"))
  content.addView(entryRow(R.drawable.ic_storage_drive,"Google Cloud 콘솔 열기",
   "프로젝트와 OAuth 설정"){
   openGuideLink("https://console.cloud.google.com/")
  })
  content.addView(entryRow(R.drawable.ic_storage_drive,"rclone Google Drive 설정 안내",
   "개인 client_id 생성과 rclone config 절차"){
   openGuideLink("https://rclone.org/drive/#making-your-own-client-id")
  })
  content.addView(entryRow(R.drawable.ic_storage_drive,"Google Drive 권한 안내",
   "drive와 drive.readonly 범위 및 검증 요건"){
   openGuideLink("https://developers.google.com/workspace/drive/api/guides/api-specific-auth")
  })
 }
 private fun openGuideLink(url:String) {
  runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
   .onFailure{message("브라우저를 열 수 없습니다: $url")}
 }
 private fun renderFonts(main:LinearLayout) {
  val content=scrollContent(main)
  fontsRevision=ReaderFonts.revision(this)
  content.addView(section("FONTS  /  설치된 글꼴"))
  content.addView(entryRow(R.drawable.ic_book,"TTF·OTF 글꼴 파일 추가",
   "기기의 Download 폴더에서 파일을 선택하세요.") {
   startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type="*/*"
    putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("font/ttf","font/otf",
     "application/x-font-ttf","application/vnd.ms-opentype","application/octet-stream"))
   },fontFileRequest)
  })
  val installed=ReaderFonts.allFamilies(this).filter{ReaderFonts.installed(this,it.id)}
  if(installed.isEmpty())content.addView(label("현재 설치된 글꼴이 없습니다.",14f,true))
  installed.forEach { family ->
   content.addView(vertical().apply {
    tag="font:${family.id}"
    layoutParams=cardLayoutParams()
    background=shape(surface(),line(),17f)
    setPadding(dp(14),dp(12),dp(14),dp(12))
    addView(label(family.label,18f).apply{
     typeface=ReaderFonts.font(this@LibraryActivity,family.id)
      ?:Typeface.create("sans-serif",Typeface.BOLD)
    })
    addView(label(family.publisher+"  ·  "+family.style,12f,true).apply{setTextColor(accent())})
    addView(label(family.description,14f))
    addView(label("기미독립선언서 · 한글 표기 일부",12f,true).apply {
     setTextColor(accent())
     setPadding(0,dp(12),0,dp(6))
    })
    val sampleFace=ReaderFonts.font(this@LibraryActivity,family.id)
    addView(label(if(sampleFace!=null)FontDeclarationPreview.excerpt
     else "이 글꼴을 불러올 수 없습니다.",18f).apply {
     typeface=sampleFace
     maxLines=6
     ellipsize=TextUtils.TruncateAt.END
     setTextColor(ink())
     setLineSpacing(dp(3).toFloat(),1.08f)
     background=shape(Color.WHITE,line(),10f)
     setPadding(dp(12),dp(12),dp(12),dp(12))
     if(sampleFace!=null) {
      contentDescription="${family.label} 기미독립선언서 예시. 전체 화면에서 보려면 누르세요."
      isClickable=true
      isFocusable=true
      setOnClickListener{showFontPreview(family)}
     }
    })
    if(sampleFace!=null)addView(label("예시를 누르면 전체 화면에서 확대·축소할 수 있습니다.",12f,true))
    addView(action("글꼴 삭제"){
     AlertDialog.Builder(this@LibraryActivity).setTitle(family.label)
      .setMessage(if(family.id.startsWith("user-"))"이 글꼴 파일을 삭제할까요?"
       else "이 글꼴을 목록에서 제거할까요? APK 원본은 앱 설정의 설치된 모듈에서 삭제할 수 있습니다.")
      .setNegativeButton("취소",null)
      .setPositiveButton("삭제"){_,_->
       if(ReaderFonts.deleteFont(this@LibraryActivity,family.id))render()
       else message("글꼴을 삭제할 수 없습니다.")
      }.show()
    })
   })
  }
 }

 private fun showFontPreview(family:ReaderFonts.Family) {
  startActivity(Intent(this,FontPreviewActivity::class.java)
   .putExtra(FontPreviewActivity.EXTRA_FAMILY_ID,family.id))
 }

 private fun renderAddSource(main:LinearLayout) {
  val content=scrollContent(main)
  content.addView(section("CONNECT A STORAGE  /  저장소 유형 선택"))
  content.addView(label("도서가 보관된 위치를 선택하세요. 저장소는 여러 개 등록할 수 있습니다.",14f))
  content.addView(entryRow(R.drawable.ic_storage_local,"로컬 폴더",
   "기기 내 선택한 폴더와 모든 하위 폴더의 도서를 표시합니다.") {
   launchLocalPicker()
  })
  content.addView(divider())
  content.addView(entryRow(R.drawable.ic_storage_webdav,"WebDAV",
   "서버 URL과 계정 정보를 입력합니다. 폴더별로 탐색합니다.") {
   startForm("webdav")
  })
  content.addView(divider())
  val rcloneReady=RclonePluginClient.available(this)
  if(!BuildConfig.LITE){content.addView(entryRow(R.drawable.ic_storage_drive,"Google Drive",
   "Google 계정으로 로그인하고 쓰기 권한을 요청합니다. 내 드라이브 또는 공유 드라이브 1개를 선택합니다.") {
    beginGoogleLogin(null)
  })
  content.addView(divider())}
  content.addView(entryRow(R.drawable.ic_storage_drive,"RClone",
   if(rcloneReady)"rclone.conf에서 연결 정보를 불러옵니다." else
    "rclone 확장 APK를 외부에서 설치하면 사용할 수 있습니다.") {
   if(rcloneReady)startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    type="*/*";addCategory(Intent.CATEGORY_OPENABLE)
   },driveRequest)
  }.apply {if(!rcloneReady){isEnabled=false;alpha=0.45f}})
  content.addView(divider())
  content.addView(entryRow(R.drawable.ic_storage_ftp,"FTP",
   "FTP 서버 주소와 계정 정보를 입력합니다. 폴더별로 탐색합니다.") {
   startForm("ftp")
  })
  content.addView(entryRow(R.drawable.storage_icon_sftp,"SFTP (SSH)",
   "서버 호스트키 SHA-256을 검증하며 도서와 YAML을 읽기 전용으로 탐색합니다.") {
   startForm("sftp")
  })
  content.addView(entryRow(R.drawable.ic_storage_bookoasis,"북오아시스 (BookOasis)",
   "서버 주소와 계정으로 카테고리·시리즈·검색·스트리밍 독서를 연결합니다.") {
   startForm("bookoasis")
  })
  content.addView(divider())
 }
  private fun beginGoogleLogin(sourceId:String?=null) {
  if(BuildConfig.LITE){message("Google 로그인은 Lite 버전에서 사용할 수 없습니다.");return}
  googleAuthEditingSourceId=sourceId
   googleAuthSelectedEmail=null
   runCatching {startActivityForResult(AccountManager.newChooseAccountIntent(
    null,null,arrayOf("com.google"),null,null,null,null),googleAccountRequest)}
    .onFailure{message("Google 계정 선택 창을 열 수 없습니다: "+(it.message?:"계정 설정을 확인하세요."))}
  }

  private fun authorizeGoogleAccount(email:String) {
   if(BuildConfig.LITE){message("Google 로그인은 Lite 버전에서 사용할 수 없습니다.");return}
   googleAuthSelectedEmail=email
   Identity.getAuthorizationClient(this).authorize(GoogleDriveSignIn.request(email,requestWrite=true))
   .addOnSuccessListener{result->
    if(result.hasResolution()) {
     val consent=result.pendingIntent
     if(consent==null){message("Google 로그인 창을 열 수 없습니다.");return@addOnSuccessListener}
     runCatching{startIntentSenderForResult(consent.intentSender,
      googleAuthorizationRequest,null,0,0,0)}
      .onFailure{message("Google 로그인 실패: "+(it.message?:"승인 창을 열 수 없습니다."))}
    } else completeGoogleLogin(result)
    }.addOnFailureListener{
     message("Google Drive 쓰기 권한 요청 실패: "+
      (it.localizedMessage?:"Google Play 서비스와 계정 권한을 확인하세요."))
    }
 }

 private fun completeGoogleLogin(result:AuthorizationResult) {
  message("Google 계정과 공유 드라이브 목록을 확인하는 중...")
  operations.submit {
   val outcome=runCatching {
    val session=GoogleDriveSignIn.sessionFromAuthorization(result)
     check(session.email.equals(googleAuthSelectedEmail,ignoreCase=true)){
      "선택한 Google 계정과 승인된 계정이 다릅니다. 다시 선택하세요."}
     check(session.writeGranted){
      "Google Drive 쓰기 권한이 승인되지 않았습니다. 계정을 다시 선택하고 Drive 권한을 승인하세요."}
    session to GoogleDriveSignIn.sharedDrives(session.token)
   }
   runOnUiThread{
    if(isDestroyed)return@runOnUiThread
    outcome.onSuccess{(session,drives)->chooseGoogleDrive(session,drives)}
     .onFailure{error->
      message("Google Drive 연결 실패: "+(error.message?:"로그인 권한 또는 Drive API 상태를 확인하세요."))
     }
   }
  }
 }

 /**
  * Google Play Services owns its authorization sheet; this is the app-owned
  * list after OAuth completes. Drive entries use the same ReaderDesign tokens
  * as the library in light, dark, automatic, and E-Ink modes.
  */
 private fun showThemedGoogleDrivePicker(
  email:String,drives:List<GoogleDriveSignIn.SharedDrive>,
  onSelect:(Int)->Unit
 ) {
  val dialog=Dialog(this)
  dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
  val root=vertical().apply {
   setPadding(dp(18),dp(18),dp(18),dp(16))
   background=shape(surface(),line(),20f)
   clipToOutline=true
  }
  root.addView(label("연결할 드라이브 한 개 선택",20f).apply {
   typeface=Typeface.create("sans-serif",Typeface.BOLD)
   setPadding(dp(2),0,dp(2),dp(7))
  })
  root.addView(label("Google 계정 · "+email,12f,true).apply {
   setPadding(dp(2),0,dp(2),dp(9))
   maxLines=2;ellipsize=TextUtils.TruncateAt.END
  })
  root.addView(label("내 드라이브 또는 공유 드라이브를 한 개 선택하세요.",13f,true).apply {
   setPadding(dp(2),0,dp(2),dp(14))
  })
  val scroll=ScrollView(this).apply {
   isFillViewport=false
   clipToPadding=false
   isVerticalScrollBarEnabled=true
   setBackgroundColor(Color.TRANSPARENT)
  }
  val options=vertical().apply {
   setPadding(dp(1),dp(2),dp(1),dp(2))
  }
  for(index in 0..drives.size) {
   val shared=drives.getOrNull(index-1)
   val title=shared?.name?.ifBlank{"공유 드라이브"}?:"내 드라이브 (My Drive)"
   val subtitle=if(shared==null)"Google Drive · 개인 드라이브"
    else "Google Workspace · 공유 드라이브"
   val row=horizontal().apply {
    minimumHeight=dp(68)
    setPadding(dp(12),dp(9),dp(10),dp(9))
    background=ripple(raised(),line(),13f)
    isClickable=true
    isFocusable=true
    contentDescription=title+" · "+subtitle+" 선택"
    setOnClickListener {
     dialog.dismiss()
     if(!isFinishing&&!isDestroyed)onSelect(index)
    }
   }
   row.addView(icon(R.drawable.ic_storage_drive,24,accent()).apply {
    background=shape(secondaryTint(),secondaryTint(),11f)
    setPadding(dp(9),dp(9),dp(9),dp(9))
   },LinearLayout.LayoutParams(dp(46),dp(46)))
   val texts=vertical().apply {setPadding(dp(11),0,dp(4),0)}
   texts.addView(label(title,15f).apply {
    typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
    maxLines=2;ellipsize=TextUtils.TruncateAt.END
   })
   texts.addView(label(subtitle,12f,true).apply {
    maxLines=1;ellipsize=TextUtils.TruncateAt.END
   })
   row.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
   row.addView(icon(R.drawable.ic_chevron_right,20,muted()))
   options.addView(row,LinearLayout.LayoutParams(-1,-2).apply{
    bottomMargin=dp(8)
   })
  }
  scroll.addView(options,ViewGroup.LayoutParams(-1,-2))
  val maxListHeight=(resources.displayMetrics.heightPixels*.55f).toInt()
   .coerceAtLeast(dp(92))
  val desiredHeight=(drives.size+1).coerceAtMost(100)*dp(84)+dp(8)
  root.addView(scroll,LinearLayout.LayoutParams(-1,
   minOf(maxListHeight,desiredHeight)))
  root.addView(action("취소"){dialog.dismiss()},
   LinearLayout.LayoutParams(-1,dp(48)).apply {topMargin=dp(12)})
  dialog.setContentView(root)
  dialog.setCancelable(true)
  dialog.setCanceledOnTouchOutside(true)
  dialog.window?.apply {
   setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
   addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
   attributes=attributes.apply{dimAmount=if(dark())0.60f else 0.38f}
  }
  dialog.show()
  dialog.window?.setLayout(
   minOf(resources.displayMetrics.widthPixels-dp(32),dp(480)),
   WindowManager.LayoutParams.WRAP_CONTENT)
 }

 private fun chooseGoogleDrive(session:GoogleDriveSignIn.Session,
  drives:List<GoogleDriveSignIn.SharedDrive>) {
  val email=session.email
  showThemedGoogleDrivePicker(email,drives){selected->
    val shared=drives.getOrNull(selected-1)
    val config=JSONObject().put("type","drive")
     .put(GoogleDriveSignIn.AUTH_KEY,true)
     .put(GoogleDriveSignIn.ACCOUNT_KEY,email)
     .put("scope",if(session.writeGranted)"drive" else "drive.readonly")
     .put("team_drive",shared?.id.orEmpty())
     .put("root_folder_id","")
    fun commit(rootId:String) {
     config.put("root_folder_id",rootId)
     val existing=repository.sources()
    val previous=existing.firstOrNull{it.id==googleAuthEditingSourceId &&
     runCatching{JSONObject(it.extra).optBoolean(GoogleDriveSignIn.AUTH_KEY)}.getOrDefault(false)}
    val prefix=if(shared==null)"Google Drive · 내 드라이브" else
     "Google 공유 드라이브 · "+shared.name
    var candidate=previous?.name?.takeIf{it.isNotBlank()}?:prefix
    var i=2
    while(existing.any{it.id!=previous?.id&&it.name==candidate})candidate="$prefix (${i++})"
    val source=LibrarySource(previous?.id?:UUID.randomUUID().toString(),"drive",candidate,"",
     extra=config.toString())
    if(previous!=null) {
     val old=JSONObject(previous.extra)
     if(!old.optString(GoogleDriveSignIn.ACCOUNT_KEY).equals(email,ignoreCase=true)||
      old.optString("team_drive")!=shared?.id.orEmpty()||
      old.optString("root_folder_id")!=rootId)repository.clearCache(previous.id)
    }
    repository.save(if(previous==null)existing+source else
     existing.map{if(it.id==previous.id)source else it})
    googleAuthEditingSourceId=null
    editing=null
    showSource(source)
     message((if(shared==null)"내 드라이브를 연결했습니다." else
      "공유 드라이브 '"+shared.name+"'을 연결했습니다.")+
      if(session.writeGranted)" 폴더 정리와 YAML 생성에 필요한 쓰기 권한을 확인합니다."
      else " 쓰기 권한이 없어 읽기 전용으로 연결했습니다.")
    }
    // Folder picker comes before saving: no simultaneous catalog scan competes
    // for rclone's native lock. Cancel connects the chosen Drive at its root.
    showDriveRootFolderPicker(config,onSelected={id->commit(id)},onCanceled={commit("")})
  }
 }
 /**
  * Browse a short-lived rclone filesystem. Custom GDS starts at its imported
  * root, which may be the only accessible subtree; OAuth and ordinary Drive
  * start above their selected root. Persist provider IDs, never display paths.
  */
 private fun showDriveRootFolderPicker(
  connection:JSONObject,
  onSelected:(String)->Unit,
  onCanceled:(()->Unit)?=null
 ) {
  if(isFinishing||isDestroyed)return
  driveFolderPicker?.dismiss()
  val imported=JSONObject(connection.toString())
  val gds=imported.optBoolean("ckreader_gds")
  val initialId=imported.optString("root_folder_id")
  val usesImportedGdsRoot=gds && RcloneGdsCredentials.custom(imported) && initialId.isNotBlank()
  val transientConfig=DriveFolderPickerPolicy.directoryConfig(imported)
  val tempId="ckreader-picker-"+UUID.randomUUID().toString()
  var browsingSource=LibrarySource(tempId,"drive","폴더 탐색","",
   extra=transientConfig.toString())
  val bridge=DriveAccess(applicationContext,repository)
  val dialog=Dialog(this)
  driveFolderPicker=dialog
  dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
  val root=vertical().apply {
   setPadding(dp(16),dp(16),dp(16),dp(14))
   background=shape(surface(),line(),18f)
  }
  root.addView(label(if(gds)"GDS 폴더 ID 선택" else "루트 폴더 ID 선택",20f))
  root.addView(label("현재 계정에서 접근할 수 있는 폴더를 선택하세요.",12f,true))
  val current=label("",13f).apply{
   maxLines=2;ellipsize=TextUtils.TruncateAt.END
   setPadding(dp(4),dp(12),dp(4),dp(8))
  }
  root.addView(current)
  val toolbar=horizontal()
  val up=action("상위 폴더"){}
  toolbar.addView(up,LinearLayout.LayoutParams(0,dp(45),1f))
  val chosen=action("현재 폴더 선택",true){}
  toolbar.addView(chosen,LinearLayout.LayoutParams(0,dp(45),1f))
  root.addView(toolbar,LinearLayout.LayoutParams(-1,-2))
  val statusLine=label("폴더 목록 조회 중…",12f,true)
  root.addView(statusLine)
  val list=vertical().apply {
   setPadding(dp(2),dp(4),dp(2),dp(4))
  }
  val scroll=ScrollView(this).apply {
   isFillViewport=false
   isVerticalScrollBarEnabled=true
   setBackgroundColor(Color.TRANSPARENT)
   addView(list,ViewGroup.LayoutParams(-1,-2))
  }
  root.addView(scroll,LinearLayout.LayoutParams(-1,
   minOf((resources.displayMetrics.heightPixels*.48f).toInt(),dp(460)).coerceAtLeast(dp(180))))
  root.addView(action("취소"){dialog.dismiss()},
   LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(10)})
  dialog.setContentView(root)
  dialog.setCancelable(true)
  dialog.setCanceledOnTouchOutside(true)
  dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
  var requestVersion=0
  var canceledByUser=true
  var triedGdsFallback=false
  val stack=ArrayList<DriveFolderPickerPolicy.Node>().apply {
   add(DriveFolderPickerPolicy.Node("",if(usesImportedGdsRoot)initialId else "",
    if(gds)"GDS 기본 폴더" else "드라이브 최상위"))
  }
  fun updateHeader(ready:Boolean){
   val selected=stack.last()
   current.text="현재 위치: "+stack.joinToString(" / "){it.name}+
    (if(selected.id.isNotEmpty())"\nID: "+selected.id else "")
   up.isEnabled=stack.size>1
   up.alpha=if(stack.size>1)1f else .45f
   val valid=DriveFolderPickerPolicy.canSelect(selected,ready)
   chosen.isEnabled=valid
   chosen.alpha=if(valid)1f else .45f
  }
  fun showNotice(text:String){
   list.removeAllViews()
   list.addView(label(text,13f,true).apply{setPadding(dp(12),dp(26),dp(12),dp(26))})
  }
  fun rowFor(item:RcloneBridge.Item,enter:()->Unit):LinearLayout {
   val folderId=DriveFolderPickerPolicy.rootFolderId(item.id)
   val description=if(folderId!=null)
    "폴더 ID · "+folderId else "폴더 ID 확인 불가 · 하위 폴더 탐색만 가능"
   return entryRow(R.drawable.ic_folder,item.name,description,onClick=enter)
  }
  lateinit var load:()->Unit
  load={
   val requested=stack.last()
   val version=++requestVersion
   updateHeader(false)
   statusLine.text="하위 폴더 조회 중…"
   showNotice("접근 가능한 폴더를 확인하고 있습니다.")
   scroll.scrollTo(0,0)
   try{
    operations.submit {
     val result=runCatching {
      var entries=runCatching{bridge.list(browsingSource,requested.path,foldersOnlyWithIds=true)}
      // A stale imported root may have lost access. In that case, try the
      // current GDS service default without changing the saved connection.
      if(entries.isFailure && usesImportedGdsRoot &&
        !triedGdsFallback && requested.path.isEmpty()){
       triedGdsFallback=true
       browsingSource=browsingSource.copy(extra=JSONObject(imported.toString())
        .put("root_folder_id","").toString())
       entries=runCatching{bridge.list(browsingSource,"",foldersOnlyWithIds=true)}
      }
      entries.getOrThrow().filter {item->
       item.directory && item.name!="." && item.name!=".." &&
        item.name.isNotBlank() && '/' !in item.name && '\\' !in item.name
      }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER){it.name})
     }
     runOnUiThread {
      if(isDestroyed||isFinishing||!dialog.isShowing||version!=requestVersion)return@runOnUiThread
      if(requested.path.isEmpty()&&triedGdsFallback&&result.isSuccess){
       stack[0]=DriveFolderPickerPolicy.Node("","","GDS 기본 폴더")
      }
      result.onSuccess { folders->
       updateHeader(true)
       statusLine.text="하위 폴더 "+folders.size+"개 · 폴더를 누르면 안으로 이동합니다."
       list.removeAllViews()
       if(folders.isEmpty())showNotice("이 위치에 하위 폴더가 없습니다. 현재 폴더를 선택할 수 있습니다.")
       // Bound view allocation even for Drive folders with thousands of
       // subdirectories. '더 보기' can reach every item without an OOM burst.
       var displayed=0
       lateinit var appendPage:()->Unit
       appendPage={
        val next=minOf(displayed+100,folders.size)
        for(index in displayed until next){
         val item=folders[index]
         val nextPath=runCatching{DriveFolderPickerPolicy.child(requested.path,item.name)}
          .getOrNull()?:continue
         list.addView(rowFor(item){
          if(stack.size>=80)message("폴더 깊이가 너무 깊습니다.")
          else {
           stack.add(DriveFolderPickerPolicy.Node(nextPath,
            DriveFolderPickerPolicy.rootFolderId(item.id).orEmpty(),item.name))
           load()
          }
         },cardLayoutParams())
        }
        displayed=next
        if(displayed<folders.size) {
         val more=action("폴더 더 보기 · "+displayed+" / "+folders.size){}
         more.setOnClickListener{
          list.removeView(more)
          appendPage()
         }
         list.addView(more,cardLayoutParams())
        }
       }
       appendPage()
      }.onFailure {error->
       updateHeader(false)
       statusLine.text="폴더 목록을 읽지 못했습니다."
       showNotice("접근 오류: "+(error.message?:"저장소 상태를 확인하세요.").take(180))
       list.addView(action("다시 시도"){load()},cardLayoutParams())
      }
     }
    }
   }catch(_:java.util.concurrent.RejectedExecutionException){
    statusLine.text="폴더 탐색을 시작할 수 없습니다."
   }
  }
  up.setOnClickListener{
   if(stack.size>1){stack.removeAt(stack.lastIndex);load()}
  }
  chosen.setOnClickListener{
   val selected=stack.last()
   if(!chosen.isEnabled)return@setOnClickListener
   val id=selected.id
   if(id.isNotEmpty() && !DriveFolderPickerPolicy.validId(id)){
    message("올바른 폴더 ID를 확인할 수 없습니다.");return@setOnClickListener
   }
   canceledByUser=false
   dialog.dismiss()
   if(!isFinishing&&!isDestroyed)onSelected(id)
  }
  dialog.setOnDismissListener{
   ++requestVersion
   if(driveFolderPicker===dialog)driveFolderPicker=null
   runCatching{operations.submit {
    runCatching{bridge.releaseFolderPicker(browsingSource)}
     .onFailure{android.util.Log.w("ckReader","임시 폴더 탐색 연결 해제 실패",it)}
   }}
   if(canceledByUser&&!isFinishing&&!isDestroyed)onCanceled?.invoke()
  }
  dialog.show()
  dialog.window?.setLayout(
   minOf(resources.displayMetrics.widthPixels-dp(24),dp(520)),
   WindowManager.LayoutParams.WRAP_CONTENT)
  updateHeader(false)
  load()
 }

 private fun startForm(type:String,source:LibrarySource?=null,preset:String="") {
  if(type=="drive"&&source?.let{DriveAccess.google(it)}!=true&&!RclonePluginClient.available(this)) {
   message("rclone 확장앱을 설치해야 Google Drive 설정을 수정할 수 있습니다.")
   return
  }
  formReturnPage=if(source!=null)page else Page.ADD_SOURCE
  editing=source;formType=type;formPreset=preset
  page=Page.FORM;render()
 }
 private fun formInput(target:LinearLayout,title:String,hint:String,value:String="",password:Boolean=false):EditText {
  target.addView(label(title,12f,true).apply {setPadding(dp(2),dp(15),0,dp(5))})
  return EditText(this).apply {
   this.hint=hint;setSingleLine(true);setText(value)
   textSize=15f;setTextColor(ink());setHintTextColor(muted())
   background=shape(surface(),line(),13f)
   setPadding(dp(14),0,dp(14),0)
   minHeight=dp(54);minimumHeight=dp(54)
   setOnFocusChangeListener { _,focused ->
    background=shape(if(focused)raised() else surface(),
     if(focused)accent() else line(),13f)
   }
   if(password)inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
   target.addView(this,LinearLayout.LayoutParams(-1,dp(54)))
  }
 }
 private fun renderForm(main:LinearLayout) {
  val content=scrollContent(main)
  val type=formType
  val existing=editing
  val savedDrive=runCatching{JSONObject(existing?.extra ?: pendingDriveConfig)}.getOrElse{JSONObject()}
  content.addView(section("CONNECTION  /  "+if(type=="drive"&&
   !savedDrive.optBoolean(GoogleDriveSignIn.AUTH_KEY))"RClone" else sourceType(type)))
  if(existing!=null) {
   val row=entryRow(sourceIcon(existing.type),"저장소 아이콘","내장 아이콘 선택 또는 이미지 추가",
    decorateIcon={bindStorageIcon(it,existing)}){showStorageIconPicker(existing)}
   row.getChildAt(0).layoutParams=LinearLayout.LayoutParams(dp(52),dp(52))
   content.addView(row)
  }
  val name=formInput(content,"저장소 이름","예: 내가 사용하는 도서관",
   existing?.name ?: formPreset)
  val gds=type=="drive" && savedDrive.optBoolean("ckreader_gds")
  val googleAuth=type=="drive" && savedDrive.optBoolean(GoogleDriveSignIn.AUTH_KEY)
  if(type=="drive")content.addView(label("드라이브 구분: "+when {
   googleAuth->"Google Drive · "+savedDrive.optString(GoogleDriveSignIn.ACCOUNT_KEY)
   gds->"GDS (rclone.conf 설정 유지)"
   else->"일반 (rclone)"
  },12f,true))
  if(googleAuth&&!DriveWritePolicy.googleWriteScope(savedDrive.toString()))
   content.addView(label("현재 읽기 전용입니다. 쓰기 권한을 사용하려면 Google 계정을 다시 승인하세요.",12f,true))
  if(googleAuth&&!BuildConfig.LITE)content.addView(action("Google 계정 / 드라이브 다시 선택 · 쓰기 권한 요청"){
   beginGoogleLogin(existing?.id)
  })
  val location=if(type!="local"&&!gds)formInput(content,
   if(type=="drive")"Google Drive 폴더" else if(type=="bookoasis")"북오아시스 서버 URL" else "서버 폴더 URL",
   when(type) {
    "ftp"->"ftp://example.com:21/books"
    "sftp"->"sftp://example.com:22/books"
    "webdav"->"https://example.com/webdav/books/"
    "bookoasis"->"https://bookoasis.example.com/"
    else->"비우면 내 드라이브 전체"
   },existing?.location.orEmpty()
  ) else null
  if(type=="local") {
   content.addView(label("등록된 폴더: "+existing?.location.orEmpty(),12f,true))
   content.addView(action("폴더 위치 변경"){launchLocalPicker(existing)})
  }
  val user=if(type=="webdav" || type=="ftp" || type=="sftp" || type=="bookoasis")
   formInput(content,"사용자 이름","아이디",existing?.user.orEmpty()) else null
  val secret=if(type=="webdav" || type=="ftp" || type=="sftp" || type=="bookoasis")
   formInput(content,"비밀번호","비밀번호",existing?.secret.orEmpty(),true) else null
  val hostFingerprint=if(type=="sftp")formInput(content,"SFTP 서버 호스트키 SHA256 (필수)",
   "SHA256:xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
   runCatching{JSONObject(existing?.extra?:"{}").optString("host_key_sha256")}.getOrDefault("")) else null
  if(type=="sftp")content.addView(label("서버 운영자가 제공한 SSH 호스트키 지문을 입력하세요. 다른 서버로의 연결을 차단하기 위해 필수입니다. SFTP는 읽기 전용입니다.",12f,true))
  var bookOasisAdult=runCatching{JSONObject(existing?.extra?:"{}").optString("db_type")=="adult"}.getOrDefault(false)
  if(type=="bookoasis") {
   content.addView(label("도서 범위",12f,true))
   content.addView(label("일반 라이브러리가 기본입니다. 성인 목록은 서버 계정 권한이 필요합니다.",12f,true))
   // Keep scope selection via an in-place switch rather than rebuilding the form.
   content.addView(android.widget.Switch(this).apply{
    text="성인 라이브러리 사용";isChecked=bookOasisAdult
    setTextColor(ink())
    setOnCheckedChangeListener{_,selected->bookOasisAdult=selected}
   })
  }
  val driveConfig=if(type=="drive")savedDrive else null
  val teamDrive=if(type=="drive"&&!gds&&!googleAuth)
   formInput(content,"공유 드라이브 ID (team_drive)","비우면 내 드라이브",
    driveConfig?.optString("team_drive").orEmpty()) else null
  val rootFolder=if(type=="drive")
   formInput(content,if(gds)"GDS 폴더 ID (root_folder_id)" else "루트 폴더 ID (root_folder_id)",
    if(gds)"비우면 rclone.conf/GDS 기본 폴더" else "비우면 드라이브 기본 폴더",
    driveConfig?.optString("root_folder_id").orEmpty()) else null
  if(type=="drive"&&driveConfig!=null) {
   content.addView(action(if(gds)"GDS 폴더 ID · 목록에서 선택" else "루트 폴더 ID · 목록에서 선택"){
    // Include currently edited team_drive when browsing, not stale persisted values.
    val candidates=JSONObject(driveConfig.toString())
    if(!gds&&!googleAuth){
     candidates.put("team_drive",teamDrive?.text?.toString()?.trim().orEmpty())
    }
    candidates.put("root_folder_id",rootFolder?.text?.toString()?.trim().orEmpty())
    showDriveRootFolderPicker(candidates,onSelected={folderId->
     rootFolder?.setText(folderId)
     rootFolder?.let{it.setSelection(it.text?.length?:0)}
     // An old rclone path would restrict the newly selected subtree.
     location?.setText("")
     message(if(folderId.isBlank())"드라이브 기본 폴더를 선택했습니다."
      else "폴더 ID를 입력했습니다. 저장소 저장을 누르면 적용됩니다.")
    })
   })
   content.addView(label("폴더를 선택하면 ID가 자동 입력됩니다. 하위 폴더로 이동하거나 상위 폴더로 돌아올 수 있으며, 실제 적용은 '저장소 저장'을 눌러야 합니다.",12f,true))
  }
  val clientId=if(type=="drive"&&!gds&&!googleAuth)
   formInput(content,"OAuth client_id","OAuth 클라이언트 ID",driveConfig?.optString("client_id").orEmpty()) else null
  val clientSecret=if(type=="drive"&&!gds&&!googleAuth)
   formInput(content,"OAuth client_secret","OAuth 클라이언트 비밀키",driveConfig?.optString("client_secret").orEmpty(),true) else null
  val error=label("",12f,true).apply {setTextColor(danger())}
  content.addView(error)
  content.addView(action("저장소 저장",true) {
   val n=name.text.toString().trim()
   val loc=location?.text?.toString()?.trim() ?: existing?.location.orEmpty()
   val valid=n.isNotBlank() && when(type) {
    "webdav"->loc.startsWith("https://") || loc.startsWith("http://")
    "ftp"->loc.startsWith("ftp://")
    "sftp"->loc.startsWith("sftp://")&&user?.text?.isNotBlank()==true&&
     secret?.text?.isNotBlank()==true&&SftpStorage.validFingerprint(hostFingerprint?.text?.toString().orEmpty())
    "drive"->driveConfig!=null && if(googleAuth)driveConfig.optString(GoogleDriveSignIn.ACCOUNT_KEY).isNotBlank()
    else if(gds)RcloneGdsCredentials.usable(driveConfig)
    else (driveConfig.optString("token").isNotBlank() ||
     driveConfig.optString("service_account_file").isNotBlank())
    "bookoasis"->(loc.startsWith("https://")||loc.startsWith("http://")) &&
     user?.text?.isNotBlank()==true && secret?.text?.isNotBlank()==true
    "local"->loc.startsWith("content://")
    else->false
   }
   if(!valid) {
    error.text="이름, 연결 주소 및 연결 정보 형식을 확인하세요."
    return@action
   }
   if(type=="drive") {
    val id=rootFolder?.text?.toString()?.trim().orEmpty()
    if(id.isNotEmpty() && !Regex("[A-Za-z0-9_-]{1,256}").matches(id)) {
     error.text="폴더 URL이 아닌 Google Drive 폴더 ID만 입력하세요."
     return@action
    }
    // In GDS mode only the root folder ID is user-editable; retain all
    // imported GDS account, endpoint, mode and backend options verbatim.
    driveConfig?.put("root_folder_id",id)
    if(!gds&&!googleAuth) {
     driveConfig?.put("client_id",clientId?.text?.toString().orEmpty())
     driveConfig?.put("client_secret",clientSecret?.text?.toString().orEmpty())
     driveConfig?.put("team_drive",teamDrive?.text?.toString()?.trim().orEmpty())
    }
   }
   val previous=repository.sources()
   val effectiveOld=existing ?: if(type=="drive")previous.firstOrNull { it.type=="drive" && it.name==n } else null
   val newSource=LibrarySource(
    effectiveOld?.id ?: UUID.randomUUID().toString(),type,n,loc,
    user?.text?.toString().orEmpty(),secret?.text?.toString().orEmpty(),
    if(type=="bookoasis")JSONObject().put("db_type",if(bookOasisAdult)"adult" else "general").toString()
    else if(type=="sftp")JSONObject().put("host_key_sha256",hostFingerprint?.text?.toString()?.trim().orEmpty()).toString()
    else driveConfig?.toString() ?: effectiveOld?.extra.orEmpty()
   )
   fun commit() {
    if(effectiveOld!=null) {
     repository.clearCache(effectiveOld.id)
     if(effectiveOld.type=="bookoasis") {
      BookOasisAccess.evict(effectiveOld);BookOasisOpdsAccess.evict(effectiveOld)
     }
    }
    repository.save(if(effectiveOld!=null)previous.map {if(it.id==effectiveOld.id)newSource else it}
     else previous+newSource)
    if(type=="bookoasis")runCatching{BookOasisCatalogWorker.enqueue(applicationContext,newSource)}
    pendingDriveConfig=""
    editing=null
    showSource(newSource)
   }
   if(type=="sftp") {
    error.text="서버 호스트키와 SFTP 접근을 확인하는 중..."
    operations.submit {
     val checked=runCatching{SftpStorage().list(newSource,"")}
     runOnUiThread{
      if(isDestroyed||page!=Page.FORM)return@runOnUiThread
      checked.onSuccess{commit()}.onFailure{error.text="SFTP 연결 실패: "+(it.message?:"SSH 접근 오류")}
     }
    }
   } else if(type=="bookoasis") {
    error.text="서버 연결 및 권한 확인 중..."
    operations.submit {
     val verified=runCatching{BookOasisAccess.client(newSource).verify()}
     runOnUiThread {
      if(isDestroyed||page!=Page.FORM)return@runOnUiThread
      verified.onSuccess{commit()}.onFailure{
       error.text="연결 실패: "+(it.message?:"알 수 없는 오류")
      }
     }
    }
   } else commit()
  })
  content.addView(label("계정 정보는 Android Keystore를 통해 암호화해 저장합니다.",11f,true))
 }
 private fun renderDriveRemotes(main:LinearLayout) {
  val content=scrollContent(main)
  content.addView(section("RCLONE  /  리모트 선택"))
  content.addView(label("가져온 설정에서 연결할 Google Drive를 선택하세요.",14f))
  driveRemotes.forEach { (name,data) ->
   content.addView(entryRow(R.drawable.ic_storage_drive,name,"일반 / GDS 선택") {
    AlertDialog.Builder(this).setTitle("드라이브 구분").setItems(arrayOf("일반","GDS")){_,choice->
     val options=JSONObject(data.toString()).apply {
      remove(GoogleDriveSignIn.AUTH_KEY);remove(GoogleDriveSignIn.ACCOUNT_KEY)
     }.put("ckreader_gds",choice==1)
      .put("ckreader_remote_name",name)
     pendingDriveConfig=options.toString()
     startForm("drive",preset=name)
    }.show()
   })
   content.addView(divider())
  }
 }
 private fun renderManage(main:LinearLayout) {
  val content=scrollContent(main)
  content.addView(section("STORAGES  /  연결 관리"))
  content.addView(entryRow(R.drawable.ic_add,"새 저장소 추가","새로운 로컬·네트워크 저장소 연결") {
   addSourceBack=Page.MANAGE;page=Page.ADD_SOURCE;render()
  })
  content.addView(divider())
  repository.sources().forEach { source ->
   content.addView(label(source.name,19f))
   content.addView(label(sourceSubtitle(source)+"  ·  "+source.location,11f,true))
   val buttons=horizontal()
   buttons.addView(action("수정"){startForm(source.type,source)})
   buttons.addView(action("아이콘"){showStorageIconPicker(source)})
   if(source.type=="local")buttons.addView(action("폴더 변경"){launchLocalPicker(source)})
   buttons.addView(action("연결 삭제"){confirmRemoveSource(source)})
   content.addView(buttons)
   content.addView(divider())
  }
 }
 private fun confirmRemoveSource(source:LibrarySource) {
  AlertDialog.Builder(this).setTitle("저장소 연결 삭제")
   .setMessage(source.name+" 연결 정보를 삭제할까요? 실제 도서 파일은 삭제되지 않습니다.")
   .setNegativeButton("취소",null)
   .setPositiveButton("연결 삭제"){_,_ ->
    repository.removeSource(source.id)
    if(selectedSourceId==source.id) {
     selectedSourceId=null;currentFolder=""
     navigation.edit().remove("source_id").remove("folder").apply()
    }
    render()
   }.show()
 }

 private fun chooseDocumentsCacheFolder() {
  startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{
   addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
   putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
    Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments"))
  },cacheTreeRequest)
 }

 private fun launchLocalPicker(existing:LibrarySource?=null) {
  editingLocalId=existing?.id
  startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
   addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
  },localRequest)
 }
 @Deprecated("Activity result API")
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==moduleUninstallRequest) {
   val name=pendingModuleUninstall?.first?:"모듈"
   pendingModuleUninstall=null
   message(if(resultCode==RESULT_OK)"$name 삭제를 완료했습니다." else "$name 삭제가 취소되었거나 실패했습니다.")
   if(page==Page.SETTINGS||page==Page.FONTS)render()
   return
  }
  if(requestCode==fontFileRequest) {
   val uri=data?.data
   if(resultCode==RESULT_OK&&uri!=null) {
    operations.submit {
     val result=runCatching{ReaderFonts.importFont(this,uri)}
     runOnUiThread{if(!isDestroyed)result
      .onSuccess{message("${it.label} 글꼴을 추가했습니다.");if(page==Page.FONTS)render()}
      .onFailure{message(it.message?:"글꼴 파일을 읽지 못했습니다.")}}
    }
   }
   return
  }
  if(requestCode==storageIconRequest) {
   val sourceId=pendingIconSourceId?:navigation.getString("pending_icon_source",null)
   pendingIconSourceId=null
   navigation.edit().remove("pending_icon_source").apply()
   val uri=data?.data
   if(resultCode!=RESULT_OK||sourceId==null||uri==null)return
   if(repository.sources().none{it.id==sourceId})return
   message("아이콘 이미지를 가져오는 중입니다.")
   operations.submit {
    val result=runCatching {
     val choice=storageIcons.importImage(uri)
     check(storageIcons.select(sourceId,choice.id)){"아이콘 선택을 저장할 수 없습니다."}
    }
    runOnUiThread {
     if(isDestroyed)return@runOnUiThread
     result.onSuccess{refreshStorageIcons();message("저장소 아이콘을 추가했습니다.")}
      .onFailure{message(it.message?:"이미지를 가져올 수 없습니다.")}
    }
   }
   return
  }
  if(requestCode==googleAuthorizationRequest) {
   if(BuildConfig.LITE)return
   if(resultCode==RESULT_OK&&data!=null){
    runCatching{Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)}
     .onSuccess{completeGoogleLogin(it)}
     .onFailure{message("Google 승인 결과를 확인할 수 없습니다: "+(it.message?:"인증 오류"))}
   } else message("Google 로그인이 취소되었습니다.")
   return
  }
  if(requestCode==googleAccountRequest) {
   if(BuildConfig.LITE)return
   val email=if(resultCode==RESULT_OK)
    data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME).orEmpty() else ""
   val type=data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE).orEmpty()
   if(email.contains('@')&&type=="com.google")authorizeGoogleAccount(email)
   else message(if(resultCode==RESULT_OK)"Google 계정을 확인할 수 없습니다." else "Google 계정 선택이 취소되었습니다.")
   return
  }
  if(resultCode!=RESULT_OK)return
  val uri=data?.data ?: return
  if(requestCode==cacheTreeRequest) {
   val flags=data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or
    Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
   if((flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0) {
    message("캐시 저장을 위한 쓰기 권한이 필요합니다.");return
   }
   val linked=runCatching{
    contentResolver.takePersistableUriPermission(uri,
     Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    repository.linkCacheDocuments(uri)
   }.getOrDefault(false)
   message(if(linked)"Documents/ckReader 캐시 폴더가 연결되었습니다."
    else "Documents 또는 Documents/ckReader 폴더를 선택하세요.")
   if(page==Page.SETTINGS)render()
   return
  }
  if(requestCode==localRequest) {
   val flags=data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
   if((flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)==0) {
    message("읽기 권한이 필요합니다.");return
   }
   val persisted=runCatching {
    if((flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0)
     contentResolver.takePersistableUriPermission(uri,
      Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    else contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
   }.isSuccess
   if(!persisted) {
    message("폴더 접근 권한을 저장할 수 없습니다.");return
   }
   val old=repository.sources().firstOrNull {it.id==editingLocalId}
   val name=androidx.documentfile.provider.DocumentFile.fromTreeUri(this,uri)?.name ?: "로컬 폴더"
   val source=if(old==null)LibrarySource(UUID.randomUUID().toString(),"local",name,uri.toString())
    else old.copy(location=uri.toString())
   if(old!=null)repository.clearCache(old.id)
   val previous=repository.sources()
   repository.save(if(old!=null)previous.map {if(it.id==old.id)source else it} else previous+source)
   editingLocalId=null
   showSource(source)
  } else if(requestCode==driveRequest) {
   if(!RclonePluginClient.available(this)){
    message("rclone 확장앱이 설치되어 있지 않습니다.");return
   }
   val config=runCatching {
    contentResolver.openInputStream(uri)!!.use { stream ->
     val out=java.io.ByteArrayOutputStream()
     val buffer=ByteArray(8192)
     while(true) {
      val n=stream.read(buffer)
      if(n<0)break
      if(n==0)continue
      require(out.size()+n<=600_000) { "rclone 설정 파일이 너무 큽니다." }
      out.write(buffer,0,n)
     }
     String(out.toByteArray(),Charsets.UTF_8)
    }
   }.getOrElse { message(it.message ?: "rclone 설정을 읽을 수 없습니다.");return }
   driveRemotes=LibraryRepository.rcloneRemotes(config)
   if(driveRemotes.isEmpty()) {
    message("Google Drive remote를 찾지 못했습니다.");return
   }
   page=Page.DRIVE_REMOTES
   render()
  }
 }
 private fun message(value:String)=Toast.makeText(this,value,Toast.LENGTH_LONG).show()
 override fun onDestroy() {
  ++scanVersion
  scanTask?.cancel(true)
  worker.shutdownNow()
  driveFolderPicker?.dismiss();driveFolderPicker=null
  operations.shutdownNow()
  ++bookOpenSequence
  bookOpenTask?.cancel(true)
  bookOpenWorker.shutdownNow()
  bookOpenTicker?.let(bookOpenHandler::removeCallbacks)
  bookOpenDialog?.dismiss();bookOpenDialog=null
  coverLoader.shutdown()
  coverRefreshWorker.shutdownNow()
  ++detailLoadVersion
  detailTask?.cancel(true)
  detailWorker.shutdownNow()
  cacheUsageWorker.shutdownNow()
  super.onDestroy()
 }
}
