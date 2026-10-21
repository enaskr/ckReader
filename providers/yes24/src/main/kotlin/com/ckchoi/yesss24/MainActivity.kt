package com.ckchoi.yesss24

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.view.*
import android.webkit.*
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

class MainActivity : Activity() {
 private lateinit var web: WebView
 private lateinit var client: Yes24Client
 private lateinit var yes24Client: Yes24Client
 private lateinit var store: Store
 private val worker = Executors.newSingleThreadExecutor()
 private val handler = Handler(Looper.getMainLooper())
 private var pendingDownload: String? = null
 private var pendingAccount = ""
 private var awaitingStorageSettings = false
 private var ready = false
 private var pendingSettingsBackup: String? = null
 private var restorePickerOpen = false
 private var transferResult: JSONObject? = null
 @Volatile private var openingBook = false
 @Volatile private var accountChanging = false
 private val poll = object : Runnable {
  override fun run() {
   if (ready) emitJob()
   handler.postDelayed(this, 700)
  }
 }
 override fun onCreate(state: Bundle?) {
  super.onCreate(state)
  runCatching { SettingsTransfer(this).recover() }.onFailure { JobService.status = "저장된 설정을 복구하지 못해 재로그인이 필요합니다." }
  val preferences=getSharedPreferences("crema_standalone",MODE_PRIVATE)
  if(!preferences.getBoolean("theme_initialized",false)){
   com.ckchoi.elibrary.common.GlobalConfig.setDark(this,false)
   runCatching { com.ckchoi.elibrary.common.GlobalConfig.setFolder(this,"yes24","CremaClub") }
   preferences.edit().putBoolean("theme_initialized",true).apply()
  }
  store = Store(this)
  store.prefs.edit().remove("autoAdd").apply()
  client = safeClient(false)
  yes24Client = safeClient(true)
  runCatching { syncDownloadAccount(false) }.onFailure { JobService.status = "설정에서 다시 로그인해주세요." }
  runCatching { syncYes24Account() }
  pendingDownload = state?.getString("pendingDownload")
  pendingAccount = state?.getString("pendingAccount").orEmpty()
  awaitingStorageSettings = state?.getBoolean("awaitingStorageSettings") ?: false
  pendingSettingsBackup=state?.getString("pendingSettingsBackup")
  restorePickerOpen=state?.getBoolean("restorePickerOpen") ?: false
  transferResult=state?.getString("transferResult")?.let{JSONObject(it)}
  androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
  window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
  val surface=if(com.ckchoi.elibrary.common.GlobalConfig.isDark(this)) Color.rgb(15,25,40) else Color.rgb(247,247,242)
  val root = FrameLayout(this).apply { setBackgroundColor(surface) }
  // Padding belongs to the native parent: CSS fixed navigation and dialogs then share the safe viewport.
  androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
   val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout() or androidx.core.view.WindowInsetsCompat.Type.ime())
   view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
   androidx.core.view.WindowInsetsCompat.CONSUMED
  }
  web = WebView(this).apply {
   setBackgroundColor(surface)
   settings.javaScriptEnabled = true; settings.domStorageEnabled = true
   settings.allowFileAccess = false; settings.allowContentAccess = false
   settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
   addJavascriptInterface(Bridge(), "Crema")
   webViewClient = object : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
     val uri = request.url
     if (uri.scheme == "https" && uri.host == "crema.local") {
      val path = uri.path.orEmpty()
      if (path.contains("..")) return emptyResponse()
      val asset = if (path == "/") "index.html" else path.removePrefix("/")
      return try { WebResourceResponse(when { asset.endsWith(".js") -> "application/javascript"; asset.endsWith(".css") -> "text/css"; asset.endsWith(".png") -> "image/png"; else -> "text/html" }, "UTF-8", assets.open("yes24/"+asset)) } catch (_: IOException) { emptyResponse() }
     }
     if (uri.scheme == "https" && uri.host == "image.yes24.com") return null
     return emptyResponse()
    }
    override fun onPageFinished(view: WebView, url: String) { ready = true; com.ckchoi.elibrary.common.GlobalConfig.applyWebTheme(this@MainActivity,view);view.evaluateJavascript("window.setAppVersion&&window.setAppVersion("+JSONObject.quote("크레마클럽 "+packageManager.getPackageInfo(packageName,0).versionName+" · "+packageName)+")",null); view.evaluateJavascript("window.setDownloadPath&&window.setDownloadPath("+JSONObject.quote(com.ckchoi.elibrary.common.GlobalConfig.relativePath(this@MainActivity,"yes24"))+")",null); web.evaluateJavascript("window.setCremaTheme&&window.setCremaTheme("+com.ckchoi.elibrary.common.GlobalConfig.isDark(this@MainActivity)+")",null); emitJob();emitTransferResult() }
   }
  }
  WebView.setWebContentsDebuggingEnabled((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
  root.addView(web, FrameLayout.LayoutParams(-1,-1)); setContentView(root)
  androidx.core.view.ViewCompat.requestApplyInsets(root)
  com.ckchoi.elibrary.common.ProviderChrome.applySystemBars(this)
  if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
  web.loadUrl("https://crema.local/")
 }
 private fun safeClient(standaloneYes24:Boolean): Yes24Client {
  return try { Yes24Client(this, standaloneYes24) }
  catch (_: Exception) {
   if (standaloneYes24) SecureStore(this,"yes24.session.v1","yes24Vault").clear() else SecureStore(this).clear()
   JobService.status = "저장된 로그인 정보를 읽지 못해 재로그인이 필요합니다."
   Yes24Client(this, standaloneYes24)
  }
 }
 private fun emptyResponse() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
 private fun emitJob() {
  val state = JSONObject().put("busy", JobService.busy).put("status", JobService.status).put("revision", JobService.revision)
  if (!isDestroyed) web.evaluateJavascript("window.downloadState && window.downloadState(" + state + ")", null)
 }
 private fun requireIdle() { if (JobService.busy) throw IOException("다운로드가 끝난 뒤 다시 시도해주세요.") }
 private fun filenamePattern(): String = store.prefs.getString("filenamePattern", DownloadPattern.DEFAULT) ?: DownloadPattern.DEFAULT
 private fun status(): JSONObject = client.status().put("yes24",yes24Client.status()).put("deleteAfterDownload", store.account().optBoolean("deleteAfterDownload", false)).put("filePermission", hasFilePermission()).put("filenamePattern", filenamePattern())
 private fun librarySettings(): JSONObject = store.account("libraries").put("selectedLibrary",store.prefs.getString("selectedLibrary",null) ?: JSONObject.NULL)
 private fun emitTransferResult() {
  val result=transferResult ?: return
  if(ready&&!isDestroyed){web.evaluateJavascript("window.settingsTransferResult && window.settingsTransferResult("+result+")",null);transferResult=null}
 }
 private fun notifyTransfer(result: JSONObject) { runOnUiThread{transferResult=result;emitTransferResult()} }
 private fun backupSettings(args: JSONObject): JSONObject {
  requireIdle();syncDownloadAccount(false)
  val libraries=store.account("libraries").optJSONArray("libraries") ?: org.json.JSONArray()
  val requested=args.optString("selectedLibrary",store.prefs.getString("selectedLibrary","") ?: "")
  val selected=if((0 until libraries.length()).any{libraries.getJSONObject(it).getString("id")==requested})requested else ""
  val settings=JSONObject().put("filenamePattern",filenamePattern())
   .put("selectedLibrary",selected).put("libraries",libraries).put("cremaOptions",store.account("cremaOptions")).put("cremaVault",client.backupVault()).put("yes24Vault",yes24Client.backupVault())
  val backup=JSONObject().put("app",SettingsBackupCodec.APP).put("schemaVersion",1).put("createdAt",java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",java.util.Locale.US).format(java.util.Date())).put("settings",settings)
  return SettingsTransfer(this).publish(backup)
 }
 @Suppress("DEPRECATION")
 private fun chooseSettingsFile(): JSONObject {
  requireIdle()
  val task=FutureTask {
   if(restorePickerOpen||pendingSettingsBackup!=null)throw IOException("백업 또는 복원이 진행 중입니다.")
   val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE);type="*/*"
    putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("application/json","text/json","text/plain","application/octet-stream"))
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if(Build.VERSION.SDK_INT>=26)putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
     android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:"+com.ckchoi.elibrary.common.GlobalConfig.relativePath(this@MainActivity,"yes24")+"/settings"))
   }
   startActivityForResult(intent,42);restorePickerOpen=true
   JSONObject().put("picking",true)
  }
  runOnUiThread(task);return task.get()
 }
 @Deprecated("Legacy Activity result API")
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==110||requestCode==111){
   if(resultCode==RESULT_OK && data?.data!=null){
    val intent=Intent(this,StandaloneReaderActivity::class.java)
    intent.data=data.data
    intent.putExtra("format",if(requestCode==110)"pdf" else "epub")
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(intent)
   }
   return
  }
  if(requestCode!=42)return
  restorePickerOpen=false
  if(resultCode!=RESULT_OK||data?.data==null){notifyTransfer(JSONObject().put("cancelled",true));return}
  val uri=data.data!!
  worker.execute {
   try {
    requireIdle()
    val transfer=SettingsTransfer(this)
    val backup=transfer.read(uri)
    pendingDownload=null;pendingAccount="";accountChanging=true
    transfer.apply(backup){client=Yes24Client(this);yes24Client=Yes24Client(this,true);syncDownloadAccount(false);syncYes24Account()}
    notifyTransfer(JSONObject().put("restored",true).put("status",status()).put("libraries",librarySettings()))
   }catch(e:Exception){runCatching{client=Yes24Client(this);yes24Client=Yes24Client(this,true)};notifyTransfer(JSONObject().put("error",if(e is IOException)e.message else "설정 파일을 복원하지 못했습니다. 파일과 저장 공간을 확인해주세요."))}
   finally{accountChanging=false}
  }
 }
 private fun cancelPendingBackup() {
  if(pendingSettingsBackup!=null){pendingSettingsBackup=null;notifyTransfer(JSONObject().put("cancelled",true))}
 }
 private fun syncDownloadAccount(verifyShelf: Boolean) {
  val old = store.account()
  val allOptions = store.account("cremaOptions")
  val previous = old.optString("userNo")
  if(previous.isNotEmpty()) {
   allOptions.put(previous,JSONObject().put("deleteAfterDownload",old.optBoolean("deleteAfterDownload",false)))
   store.save(allOptions,"cremaOptions")
  }
  val current = client.status()
  val options = (allOptions.optJSONObject(current.optString("userNo")) ?: JSONObject()).put("pattern",filenamePattern())
  if (current.optBoolean("loggedIn")) store.save(client.downloadAccount(options, verifyShelf))
  else store.save(options)
 }
 private fun syncYes24Account() {
  val current=yes24Client.status()
  val options=JSONObject().put("pattern",filenamePattern())
  if(current.optBoolean("loggedIn"))store.save(yes24Client.downloadAccount(options,false),"yes24Account")
  else store.save(options,"yes24Account")
 }
 private fun yes24ChangeAccount(user:String,remove:Boolean):JSONObject {
  requireIdle();accountChanging=true
  try {if(remove)yes24Client.removeAccount(user) else yes24Client.switchAccount(user);syncYes24Account();return status()}
  finally {accountChanging=false}
 }
 private fun yes24Login(user:String,password:String):JSONObject {
  requireIdle();accountChanging=true
  try {yes24Client.login(user.trim(),password);syncYes24Account();return status()}
  finally {runCatching{syncYes24Account()};accountChanging=false}
 }
 private fun changeAccount(user: String, remove: Boolean): JSONObject {
  requireIdle();accountChanging=true
  try {
   pendingDownload=null;pendingAccount=""
   if(remove) client.removeAccount(user) else client.switchAccount(user)
   syncDownloadAccount(false)
   return status()
  } finally { accountChanging=false }
 }
 private fun login(user: String, password: String): JSONObject {
  requireIdle(); accountChanging = true
  try {
   // Identical authentication and encrypted session persistence to CremaClub.
   client.login(user.trim(), password)
   pendingDownload = null
   syncDownloadAccount(false)
   return status()
  } finally {
   try { syncDownloadAccount(false) } finally { accountChanging = false }
  }
 }
 inner class Bridge {
  @JavascriptInterface fun setDarkMode(dark: Boolean) {
   runOnUiThread {
    com.ckchoi.elibrary.common.GlobalConfig.setDark(this@MainActivity,dark)
    com.ckchoi.elibrary.common.GlobalConfig.applyWebTheme(this@MainActivity,web)
    web.evaluateJavascript("window.setCremaTheme&&window.setCremaTheme("+dark+")",null)
   }
  }
  @JavascriptInterface fun openLocalBook(format:String) {
   if(format!="pdf" && format!="epub")return
   runOnUiThread {
    val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
     addCategory(Intent.CATEGORY_OPENABLE)
     type=if(format=="pdf")"application/pdf" else "application/epub+zip"
     addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    @Suppress("DEPRECATION")
    startActivityForResult(intent,if(format=="pdf")110 else 111)
   }
  }
  @JavascriptInterface fun call(serialized: String) {
   worker.execute {
    var id = ""
    val response = JSONObject()
    try {
     val request = JSONObject(serialized); id = request.getString("id")
     val args = request.optJSONObject("args") ?: JSONObject()
     val action = request.getString("action")
     val accountBound = action in listOf("add","remove","download","readCrema","devices","logout","downloadOptions") ||
       (action == "fetch" && args.optString("path").contains("MyBook",ignoreCase=true))
     if(accountBound && args.has("cremaAccountId") && args.getString("cremaAccountId") != client.status().optString("userId"))
      throw IOException("Crema 계정이 변경되었습니다. 현재 계정의 서재를 다시 열어주세요.")
     val result: Any = when (action) {
      "fetch" -> client.fetchPage(args.getString("path"))
      "yes24Ebook" -> client.fetchYes24Ebook(args.optString("query",""))
      "yes24EbookDetail" -> client.fetchYes24EbookDetail(args.getString("id"))
      "status" -> status()
      "login" -> login(args.getString("userId"), args.getString("password"))
      "yes24Login" -> yes24Login(args.getString("userId"),args.getString("password"))
      "yes24SwitchAccount" -> yes24ChangeAccount(args.getString("userId"),false)
      "yes24RemoveAccount" -> yes24ChangeAccount(args.getString("userId"),true)
      "yes24Logout" -> {requireIdle();yes24Client.logout();syncYes24Account();status()}
      "switchAccount" -> changeAccount(args.getString("userId"),false)
      "removeAccount" -> changeAccount(args.getString("userId"),true)
      "devices" -> { requireIdle();client.refreshDevices();syncDownloadAccount(false);status() }
      "logout" -> { requireIdle();client.logout();syncDownloadAccount(false);pendingDownload=null;status() }
      "add" -> { requireIdle(); client.add(args.getString("id")) }
      "remove" -> { requireIdle(); syncDownloadAccount(true); client.remove(args.getString("id")) }
      "download" -> {
       syncDownloadAccount(true)
       val task = FutureTask { startDownload(args.getString("id"),args.optString("title"),args.optString("author"),args.optString("extension","epub")) }; runOnUiThread(task); task.get()
      }
      "readCrema", "readPurchase" -> {
       requireIdle()
       if(openingBook)throw IOException("도서를 여는 중입니다.")
       openingBook=true
       try {
        if(action=="readCrema")syncDownloadAccount(true) else syncYes24Account()
        val file=(if(action=="readCrema")client else yes24Client).prepareReader(args.getString("id"),action=="readCrema")
        val format=file.extension.lowercase()
        runOnUiThread {
         if(!isDestroyed)startActivity(Intent(this@MainActivity,StandaloneReaderActivity::class.java).apply {
          data=Uri.fromFile(file);putExtra("format",format)
          putExtra("documentId",(if(action=="readCrema")"crema:"+client.status().optString("userId") else "yes24:"+yes24Client.status().optString("userId"))+":"+args.getString("id"))
          putExtra("deleteOnClose",true)
         }) else file.delete()
        }
        JSONObject().put("opened",true)
       } finally { openingBook=false }
      }
      "purchases" -> { requireIdle(); syncYes24Account(); yes24Client.purchases() }
      "purchaseDownload" -> {
       syncYes24Account()
       if(!hasFilePermission()){runOnUiThread{requestFilePermission()};throw IOException("저장소 권한을 허용한 뒤 다운로드를 다시 눌러주세요.")}
       val bookId=args.getString("id")
       if(DownloadPublisher.alreadyDownloaded(this@MainActivity,bookId)){
        runOnUiThread{android.widget.Toast.makeText(this@MainActivity,"이미 다운로드한 파일입니다.",android.widget.Toast.LENGTH_SHORT).show()}
        JSONObject().put("started",false).put("alreadyDownloaded",true)
       }
       else {if(!JobService.enqueuePurchase(this@MainActivity,bookId,args.optString("title"),args.optString("author"),args.optString("extension","epub"),args.optString("cover")))throw IOException("다운로드가 진행 중입니다.");JSONObject().put("started",true)}
      }
      "downloadCenterData" -> com.ckchoi.elibrary.common.DownloadCenterStore.payload(this@MainActivity,"yes24",args.optString("mode","saved"))
      "downloadCenterRead" -> {
       val id=args.getString("id")
       val saved=com.ckchoi.elibrary.common.DownloadCenterStore.saved(this@MainActivity,"yes24")
       val item=(0 until saved.length()).map{saved.getJSONObject(it)}.firstOrNull{it.optString("id")==id}
        ?:throw IOException("저장파일 목록에서 도서를 찾지 못했습니다.")
       val name=item.optString("name").ifBlank{item.optString("path")}
       val format=name.substringAfterLast('.',"").lowercase()
       if(format !in setOf("epub","pdf"))throw IOException("EPUB·PDF 파일만 바로 볼 수 있습니다.")
       val uri=Uri.parse(item.optString("uri"))
       if(uri.scheme !in setOf("content","file"))throw IOException("저장된 파일 주소를 확인할 수 없습니다.")
       try{contentResolver.openFileDescriptor(uri,"r")?.use{}?:throw IOException("저장된 파일을 열 수 없습니다.")}
       catch(e:java.io.FileNotFoundException){throw IOException("저장된 파일을 찾지 못했습니다.")}
       runOnUiThread { if(!isDestroyed)startActivity(Intent(this@MainActivity,StandaloneReaderActivity::class.java).apply{
        data=uri;putExtra("format",format);putExtra("documentId","saved:"+id)
       }) }
       JSONObject().put("opened",true)
      }
      "downloadCenterDelete" -> com.ckchoi.elibrary.common.DownloadCenterStore.delete(this@MainActivity,"yes24",args.optString("mode","saved"),args.optString("id"),args.optBoolean("deleteFile"))
      "filePermission" -> { runOnUiThread { requestFilePermission() }; JSONObject().put("granted",hasFilePermission()) }
      "voiceDataDownload" -> {
       runOnUiThread{startActivity(Intent(this@MainActivity,AiVoiceDownloadActivity::class.java))}
       JSONObject().put("opened",true)
      }
      "downloadOptions" -> { requireIdle(); val a=store.account().put("deleteAfterDownload",args.optBoolean("deleteAfterDownload"));store.save(a);status() }
      "filenamePattern" -> {
       requireIdle()
       val pattern = DownloadPattern.validate(args.getString("pattern"))
       if(!store.prefs.edit().putString("filenamePattern",pattern).commit()) throw IOException("파일명 규칙 저장 실패")
       syncDownloadAccount(false);status()
      }
      "selectLibrary" -> {
       val selected=args.getString("id")
       val libraries=store.account("libraries").optJSONArray("libraries") ?: org.json.JSONArray()
       require(selected.isEmpty()||(0 until libraries.length()).any{libraries.getJSONObject(it).getString("id")==selected})
       check(store.prefs.edit().putString("selectedLibrary",selected).commit());JSONObject().put("selectedLibrary",selected)
      }
      "librarySettings" -> librarySettings()
      "officialLibraries" -> Yes24Client.officialLibraries()
      "saveLibrarySettings" -> {
       requireIdle()
       val entries = args.getJSONArray("libraries")
       require(entries.length() <= 50)
       val ids = mutableSetOf<String>()
       for(i in 0 until entries.length()) {
        val item = entries.getJSONObject(i)
        require(ids.add(item.getString("id")))
        val uri = Uri.parse(item.getString("url"))
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null)
        require(item.getString("user").matches(Regex("[A-Za-z0-9@._-]+")))
        require(item.getString("mac").matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")))
       }
       store.save(args,"libraries"); args
      }
      "electronic" -> {
       val operation = args.getString("operation")
       val config = store.account("libraries").put("pattern",filenamePattern()).toString()
       if(operation == "download") {
        if(!hasFilePermission()) {
         runOnUiThread { requestFilePermission() }
         throw IOException("저장소 권한을 허용한 뒤 다운로드를 다시 눌러주세요.")
        }
        if(!JobService.enqueue(this@MainActivity,args.toString(),config)) throw IOException("다운로드가 진행 중입니다.")
        JSONObject().put("started",true)
       } else {
        if(operation == "borrow" || operation == "remove") requireIdle()
        Yes24LibraryNative(this@MainActivity,JSONObject(config)).operate(operation,args)
       }
      }
      else -> throw IOException("지원하지 않는 작업입니다.")
     }
     response.put("result",result)
    } catch (e: Exception) {
     val cause = e.cause ?: e
     response.put("error", if (cause is IOException || cause is org.json.JSONException) cause.message else "요청 처리 중 오류가 발생했습니다. 다시 로그인하거나 재시도해주세요.")
    }
    response.put("id",id)
    runOnUiThread { if (!isDestroyed) web.evaluateJavascript("window.nativeResult(" + response + ")",null) }
   }
  }
 }
 private fun startDownload(goods: String,bookTitle:String="",author:String="",extension:String="epub"): JSONObject {
  if (!goods.matches(Regex("[0-9]+"))) throw IOException("잘못된 상품 번호입니다.")
  if (accountChanging) throw IOException("로그인이 완료된 뒤 다시 시도해주세요.")
  val a=store.account()
  if (a.optString("userNo").isEmpty()) throw IOException("설정에서 먼저 로그인해주세요.")
  if (!hasFilePermission()) {
   pendingDownload=goods;pendingAccount=a.getString("userNo");requestFilePermission()
   return JSONObject().put("started",false).put("permissionPending",true)
  }
  if (DownloadPublisher.alreadyDownloaded(this,goods)) {
   android.widget.Toast.makeText(this,"이미 다운로드한 파일입니다.",android.widget.Toast.LENGTH_SHORT).show()
   return JSONObject().put("started",false).put("alreadyDownloaded",true)
  }
  if (!JobService.enqueue(this, goods,null,bookTitle,author,extension)) throw IOException("다운로드가 진행 중입니다.")
  emitJob()
  return JSONObject().put("started",true)
 }
 private fun hasFilePermission() = Build.VERSION.SDK_INT >= 29 || checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
 private fun requestFilePermission() {
  if (hasFilePermission()) { JobService.status=com.ckchoi.elibrary.common.GlobalConfig.relativePath(this,"yes24")+"에 저장할 수 있습니다.";emitJob();return }
  if (store.prefs.getBoolean("storagePermissionRequested",false) && !shouldShowRequestPermissionRationale(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
   AlertDialog.Builder(this).setTitle("파일 권한 설정").setMessage("앱 권한에서 저장소 권한을 허용해주세요. 돌아오면 다운로드를 이어서 시작합니다.")
    .setPositiveButton("앱 설정 열기") { _,_ -> awaitingStorageSettings=true;startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+packageName))) }
    .setNegativeButton("취소") { _,_ -> pendingDownload=null;cancelPendingBackup() }.setOnCancelListener { pendingDownload=null;cancelPendingBackup() }.show()
  } else { store.prefs.edit().putBoolean("storagePermissionRequested",true).apply();requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),28) }
 }
 private fun resumeDownload() {
  val backup=pendingSettingsBackup;pendingSettingsBackup=null
  if(backup!=null) {
   if(!hasFilePermission()){notifyTransfer(JSONObject().put("error","저장소 권한이 없어 설정을 백업하지 않았습니다."));return}
   worker.execute {
    try{notifyTransfer(backupSettings(JSONObject(backup)))}
    catch(e:Exception){notifyTransfer(JSONObject().put("error","설정 백업에 실패했습니다. 저장 공간과 권한을 확인해주세요."))}
   }
   return
  }
  val goods=pendingDownload; pendingDownload=null
  if (!hasFilePermission()) { JobService.status="저장소 권한이 없어 다운로드하지 않았습니다.";return }
  if (goods!=null && pendingAccount==store.account().optString("userNo")) runCatching { startDownload(goods) }.onFailure { JobService.status="다운로드를 다시 눌러주세요." }
 }
 override fun onRequestPermissionsResult(code:Int, permissions:Array<out String>, results:IntArray) { super.onRequestPermissionsResult(code,permissions,results);if(code==28)resumeDownload() }
 override fun onSaveInstanceState(out:Bundle) { out.putString("pendingSettingsBackup",pendingSettingsBackup);out.putBoolean("restorePickerOpen",restorePickerOpen);out.putString("transferResult",transferResult?.toString());out.putString("pendingDownload",pendingDownload);out.putString("pendingAccount",pendingAccount);out.putBoolean("awaitingStorageSettings",awaitingStorageSettings);super.onSaveInstanceState(out) }
 override fun onResume() { super.onResume();if(::web.isInitialized){com.ckchoi.elibrary.common.GlobalConfig.applyWebTheme(this,web);web.evaluateJavascript("window.setDownloadPath&&window.setDownloadPath("+JSONObject.quote(com.ckchoi.elibrary.common.GlobalConfig.relativePath(this,"yes24"))+")",null)};com.ckchoi.elibrary.common.ProviderChrome.applySystemBars(this);handler.post(poll);if(awaitingStorageSettings){awaitingStorageSettings=false;resumeDownload()} }
 override fun onPause() { handler.removeCallbacks(poll);super.onPause() }
 override fun onDestroy() { handler.removeCallbacksAndMessages(null);worker.shutdownNow();web.removeJavascriptInterface("Crema");web.destroy();super.onDestroy() }
 private fun back() { web.evaluateJavascript("window.handleBack()") { if(it=="false")com.ckchoi.elibrary.common.ExitGuard.backOrExit(this) else com.ckchoi.elibrary.common.ExitGuard.reset(this) } }
 @android.annotation.SuppressLint("GestureBackNavigation")
 @Deprecated("Legacy Android back callback") override fun onBackPressed() { back() }
}
