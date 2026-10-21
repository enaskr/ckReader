package com.ckchoi.yesss24

import android.content.Context
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.ceil

class Yes24LibraryNative(private val context: Context, private val config: JSONObject) {
 private val cache=context.getSharedPreferences("yes24.library.cache",Context.MODE_PRIVATE)
 private fun library(id:String):JSONObject {
  val a=config.optJSONArray("libraries")?:JSONArray()
  for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;if(x.optString("id")==id)return x}
  throw IOException("전자도서관 설정을 찾지 못했습니다.")
 }
 private fun enc(v:String)=URLEncoder.encode(v,"UTF-8")
 private fun postForm(url:String, values:Map<String,Any?>, ua:String="READON_HTTP", timeout:Int=20000):Any {
  fun exchange(target:String):Any {
   val c=URL(target).openConnection() as HttpURLConnection
   c.requestMethod="POST";c.doOutput=true;c.connectTimeout=10000;c.readTimeout=timeout
   c.setRequestProperty("User-Agent",ua);c.setRequestProperty("Accept","application/json");c.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
   val body=values.entries.joinToString("&"){enc(it.key)+"="+enc(it.value?.toString().orEmpty())}.toByteArray()
   c.outputStream.use{it.write(body)}
   val code=c.responseCode
   val bytes=(if(code>=400)c.errorStream else c.inputStream)?.use{it.readBytes()}?:byteArrayOf()
   c.disconnect()
   if(code>=400)throw IOException("도서관 서버가 HTTP "+code+" 오류를 반환했습니다.")
   return JSONTokener(String(bytes,Charsets.UTF_8).removePrefix("\uFEFF").trim()).nextValue()
  }
  return try{exchange(url)}catch(first:Exception){
   val alt=when{url.startsWith("https://")->"http://"+url.removePrefix("https://");url.startsWith("http://")->"https://"+url.removePrefix("http://");else->throw first}
   try{exchange(alt)}catch(_:Exception){throw first}
  }
 }
 private fun getText(url:String,ua:String,timeout:Int=30000):String {
  fun exchange(target:String):String {
   val c=URL(target).openConnection() as HttpURLConnection;c.connectTimeout=15000;c.readTimeout=timeout;c.setRequestProperty("User-Agent",ua);c.setRequestProperty("Accept","*/*")
   val code=c.responseCode;if(code>=400){c.disconnect();throw IOException("요청 실패 (HTTP "+code+")")}
   val s=c.inputStream.bufferedReader().use{it.readText()};c.disconnect();return s
  }
  return try{exchange(url)}catch(first:Exception){val alt=if(url.startsWith("https://"))"http://"+url.removePrefix("https://") else if(url.startsWith("http://"))"https://"+url.removePrefix("http://") else throw first;try{exchange(alt)}catch(_:Exception){throw first}}
 }
 private fun download(url:String,file:File){
  fun exchange(target:String){
   val c=URL(target).openConnection() as HttpURLConnection;c.connectTimeout=15000;c.readTimeout=120000;c.setRequestProperty("User-Agent","WinInet Progress");c.setRequestProperty("Accept","*/*")
   val code=c.responseCode;if(code>=400){c.disconnect();throw IOException("도서 파일 다운로드 HTTP 오류 ("+code+")")}
   file.outputStream().buffered().use{out->c.inputStream.buffered().use{it.copyTo(out)}};c.disconnect()
  }
  try{exchange(url)}catch(first:Exception){val alt=if(url.startsWith("https://"))"http://"+url.removePrefix("https://") else if(url.startsWith("http://"))"https://"+url.removePrefix("http://") else throw first;try{exchange(alt)}catch(_:Exception){throw first}}
 }
 private fun requireOk(v:Any):JSONObject {
  val o=v as? JSONObject?:throw IOException("도서관 응답 형식이 올바르지 않습니다.")
  val code=o.optString("resultCode");if(code!="100"&&code!="Y")throw IOException(o.optString("resultMessage","도서관 응답을 확인할 수 없습니다."));return o
 }
 private fun plain(v:Any?):String {
  @Suppress("DEPRECATION")
  return Html.fromHtml(v?.toString().orEmpty(),Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\n[ \t]*\n[ \t]*\n+"),"\n\n").trim()
 }
 private fun n(v:Any?):Int?=runCatching{v?.toString()?.toInt()}.getOrNull()?.coerceAtLeast(0)
 private fun normalize(item:JSONObject):JSONObject{
  val owned=n(item.opt("volumeCnt"));val lent=n(item.opt("lentCnt"));val reserved=n(item.opt("reserveCnt"));val known=owned!=null&&lent!=null&&reserved!=null
  val borrowing=item.optString("lendingFlag").equals("Y",true);val reserving=item.optString("reserveFlag").equals("Y",true);val available=known&&owned!!>lent!!&&reserved==0
  var date=item.optString("ebookYmd");if(date.matches(Regex("\\d{8}")))date=date.substring(0,4)+"-"+date.substring(4,6)+"-"+date.substring(6)
  return JSONObject().put("goods_id",item.optString("goodsId")).put("ebook_id",item.optString("epubId")).put("title",plain(item.opt("pdName")).ifBlank{"제목 없음"})
   .put("author",plain(item.opt("authorName")).replace("<","").replace(">","")).put("publisher",plain(item.opt("publisher"))).put("thumbnail",item.optString("thumbnail"))
   .put("published_date",date).put("content_sort",item.optString("contentSort")).put("lending_idx",n(item.opt("lendingIdx"))?:0).put("file_type",item.optString("fileType"))
   .put("file_size",n(item.opt("fileSize"))?:JSONObject.NULL).put("owned_count",owned?:JSONObject.NULL).put("loan_count",lent?:JSONObject.NULL).put("reserve_count",reserved?:JSONObject.NULL)
   .put("is_borrowed",borrowing).put("is_reserved",reserving).put("can_borrow",available&&!borrowing&&!reserving).put("can_reserve",known&&owned!!>0&&!available&&!borrowing&&!reserving)
   .put("availability_known",known).put("description",plain(item.opt("longDescription"))).put("contents",plain(item.opt("indexInfo")))
 }
 fun operate(operation:String,args:JSONObject,publisher:DownloadPublisher?=null):Any {
  val lib=library(args.getString("libraryId"));return when(operation){
   "browse"->browse(lib,args.optString("mode","best"),args.optString("query"),args.optInt("page",1))
   "detail"->detail(lib,args.getString("goodsId"))
   "borrow"->borrow(lib,args.getString("goodsId"))
   "sync"->sync(lib)
   "loans"->loans(lib,false)
   "remove"->remove(lib,args.getString("ebookId"))
   "download"->downloadLoan(lib,args.getString("ebookId"),publisher?:throw IOException("다운로드 저장기를 준비하지 못했습니다."))
   else->throw IOException("지원하지 않는 전자도서관 작업입니다.")
  }
 }
 private fun app(lib:JSONObject,path:String,values:MutableMap<String,Any?>):JSONObject {
  values["userId"]=lib.getString("user");return requireOk(postForm(lib.getString("url").trimEnd('/')+"/app/"+path,values))
 }
 private fun browse(lib:JSONObject,mode:String,query:String,page:Int):JSONObject{
  if(mode !in setOf("best","recommend","search")||page<1)throw IOException("올바른 목록 요청이 아닙니다.");if(mode=="search"&&query.isBlank())throw IOException("검색어를 입력해 주세요.")
  val o=app(lib,"appMain.asp",mutableMapOf("mode" to mode,"strSearch" to if(mode=="search")query else "","currentPage" to page))
  val rows=o.optJSONArray("eBookInfoList")?:JSONArray();val books=JSONArray();for(i in 0 until rows.length())rows.optJSONObject(i)?.let{books.put(normalize(it))}
  val total=o.optInt("count");return JSONObject().put("books",books).put("current_page",page).put("total_count",total).put("total_pages",maxOf(1,ceil(total/20.0).toInt()))
 }
 private fun detail(lib:JSONObject,goods:String):JSONObject{
  val o=app(lib,"appDetail.asp",mutableMapOf("goodsId" to goods));val x=o.optJSONObject("ebookInfo")?:throw IOException("도서 상세정보를 찾을 수 없습니다.");if(x.optString("goodsId")!=goods)throw IOException("도서 상세정보가 일치하지 않습니다.");return normalize(x)
 }
 private fun borrow(lib:JSONObject,goods:String):JSONObject{
  val b=detail(lib,goods);if(!b.optBoolean("can_borrow"))throw IOException("현재 대출할 수 없는 도서입니다. 최신 대출·예약 현황을 확인해 주세요.");val sort=b.optString("content_sort");if(sort.isBlank())throw IOException("도서 유형을 확인하지 못해 대출할 수 없습니다.")
  requireOk(postForm(lib.getString("url").trimEnd('/')+"/app/appProc.asp",mapOf("mode" to "lent","goodsId" to goods.padStart(3,'0'),"lendingIdx" to b.optInt("lending_idx"),"contentSort" to sort,"userId" to lib.getString("user"))))
  return JSONObject().put("goods_id",goods).put("title",b.optString("title"))
 }
 private fun value(v:Any?,keys:Set<String>):Any?{
  when(v){
   is JSONObject->{val it=v.keys();while(it.hasNext()){val k=it.next();if(k.replace("_","").lowercase() in keys)return v.opt(k)};val it2=v.keys();while(it2.hasNext()){val x=value(v.opt(it2.next()),keys);if(x!=null&&x!=JSONObject.NULL)return x}}
   is JSONArray->for(i in 0 until v.length()){val x=value(v.opt(i),keys);if(x!=null&&x!=JSONObject.NULL)return x}
  };return null
 }
 private fun findLoans(v:Any?):JSONArray{
  if(v is JSONObject){
   val keys=listOf("ebookInfoList","ebookList","loanList","lendingList");for(k in keys){val x=v.opt(k);if(x is JSONArray)return x}
   val id=value(v,setOf("epubid","ebookid","epubno"));val title=value(v,setOf("pdname","bookname","title","ebookname"));if(id!=null&&(title!=null||value(v,setOf("lendingidx","lendingindex","lendingid","lendidx","loanidx"))!=null))return JSONArray().put(v)
   val it=v.keys();while(it.hasNext()){val a=findLoans(v.opt(it.next()));if(a.length()>0)return a}
  } else if(v is JSONArray){for(i in 0 until v.length()){val a=findLoans(v.opt(i));if(a.length()>0)return a}}
  return JSONArray()
 }
 private fun loanRecord(x:JSONObject,i:Int):JSONObject{
  fun s(vararg k:String,def:String="-"):String=(value(x,k.map{it.replace("_","").lowercase()}.toSet())?.toString()?.takeIf{it.isNotBlank()}?:def)
  return JSONObject().put("title",s("pdName","bookName","title","ebookName")).put("author",s("authorName","author",def="")).put("publisher",s("publisher","publisherName",def=""))
   .put("lending_date",s("lendingDate","loanDate",def="")).put("expired_date",s("lendingExpiredDate","expiredDate","loanExpiredDate",def="")).put("ebook_id",s("epubId","ebookId","ebookID",def=""))
   .put("goods_id",s("goodsId","goodsID",def="")).put("file_type",s("fileType","format",def="")).put("thumbnail",s("thumbnail","thumbnailUrl","coverUrl",def="")).put("tts",s("tts",def="")).put("ebook_ymd",s("ebookYmd",def=""))
   .put("lending_idx",s("lendingIdx","lendingIndex","lendingID","lendIdx","loanIdx",def="")).put("sort_order",i)
 }
 private fun sync(lib:JSONObject):JSONObject{
  val o=app(lib,"appMyLibrary.asp",mutableMapOf("mode" to "lent"));val rows=findLoans(o);val books=JSONArray();for(i in 0 until rows.length()){val x=rows.optJSONObject(i)?:continue;val b=loanRecord(x,i+1);if(b.optString("ebook_id").isNotBlank())books.put(b)}
  cache.edit().putString(lib.getString("id"),books.toString()).apply();return JSONObject().put("books",books).put("count",books.length()).put("synced",true)
 }
 private fun loans(lib:JSONObject,synced:Boolean):JSONObject{val a=JSONArray(cache.getString(lib.getString("id"),"[]"));return JSONObject().put("books",a).put("count",a.length()).put("synced",synced)}
 private fun remove(lib:JSONObject,ebook:String):JSONObject{
  val a=JSONArray(cache.getString(lib.getString("id"),"[]"));val out=JSONArray();for(i in 0 until a.length()){val x=a.getJSONObject(i);if(x.optString("ebook_id")!=ebook)out.put(x)};cache.edit().putString(lib.getString("id"),out.toString()).apply();return JSONObject().put("removed",true)
 }
 private fun downloadLoan(lib:JSONObject,ebook:String,publisher:DownloadPublisher):String{
  val current=sync(lib).getJSONArray("books");var book:JSONObject?=null;for(i in 0 until current.length()){val x=current.getJSONObject(i);if(x.optString("ebook_id")==ebook){book=x;break}}
  val b=book?:throw IOException("현재 대여 목록에서 도서를 찾지 못했습니다.");if(b.optString("lending_idx").isBlank())throw IOException("대여 식별자를 받지 못했습니다. 대여 목록을 다시 조회해 주세요.")
  val read=app(lib,"appEBookRead.asp",mutableMapOf("lendingIdx" to b.getString("lending_idx"),"epubId" to ebook));val license=(value(read,setOf("key","licensekey","license"))?.toString()).orEmpty();if(license.isBlank())throw IOException("도서 키 정보를 받지 못했습니다.")
  val ver=Yes24Drm.licenseValue(license,"VER");val uuid=Yes24Drm.licenseValue(license,"UUID");val ord=Yes24Drm.licenseValue(license,"DRM_ORD");val down=Yes24Drm.html(Yes24Drm.licenseValue(license,"DOWN_URL"));val auth=Yes24Drm.html(Yes24Drm.licenseValue(license,"AUTH_URL"));var type=Yes24Drm.licenseValue(license,"F_TYPE");if(type.isBlank())type=b.optString("file_type")
  if(down.isBlank()||auth.isBlank())throw IOException("다운로드 URL 또는 인증 URL이 비어 있습니다.");publisher.status("전자도서관 라이선스 인증 중…")
  val pair=Yes24Drm.queryKeyIv(ver);val plain="VER="+ver+"&UDID="+lib.getString("mac")+"&UUID="+uuid+"&DRM_ORD="+ord+"&tk="+Yes24Drm.randomTk();val query=Yes24Drm.encryptBase64(plain.toByteArray(StandardCharsets.US_ASCII),pair.first,pair.second);val reply=getText(auth+(if("?" in auth)"&" else "?")+"QUERY="+enc(query),"UNDRNCLIENT 1.0.0.1001");val err=Yes24Drm.licenseValue(reply,"ERR_CD",false);if(err!="0")throw IOException("라이선스 인증 실패: "+err+" "+Yes24Drm.licenseValue(reply,"ERR_MSG",false));val ck=Yes24Drm.licenseValue(reply,"L_DATA",false);if(ck.isBlank())throw IOException("라이선스 응답에 L_DATA가 없습니다.")
  val key=Yes24Drm.deriveBookKey(ck,lib.getString("mac"));val work=File(context.cacheDir,"yes24-library").apply{mkdirs()};val raw=File.createTempFile("book-",".bin",work);publisher.status("전자도서관 도서 다운로드 중…");download(down,raw)
  val upper=type.uppercase();val ext=if(upper=="PDF")"pdf" else if(upper in setOf("COMIC","EPUB_COMIC"))"zip" else "epub";val out=File(work,Yes24Drm.safeName(b.optString("title")+"-"+b.optString("author")+"-"+b.optString("publisher")+"-"+ebook) + "."+ext);publisher.status("전자도서관 DRM 처리 중…");Yes24Drm.exportBook(raw,out,type,key);raw.delete();publisher.status(com.ckchoi.elibrary.common.GlobalConfig.relativePath(context,"yes24")+"에 저장 중…");publisher.publish(out.absolutePath);out.delete();return "다운로드 완료 · "+com.ckchoi.elibrary.common.GlobalConfig.relativePath(context,"yes24")
 }
}
