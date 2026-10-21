package com.ckchoi.elibrary.common

import android.app.*
import android.content.*
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object DownloadCenterStore {
    private const val PREFS = "elibrary_download_center_v1"
    private val lock = Any()
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun read(c: Context, key: String): JSONArray = try { JSONArray(prefs(c).getString(key, "[]") ?: "[]") } catch(_:Exception){ JSONArray() }
    private fun write(c: Context, key: String, a: JSONArray) { check(prefs(c).edit().putString(key,a.toString()).commit()) }
    private fun q(service:String)="q_$service"
    private fun s(service:String)="s_$service"
    private fun now()=System.currentTimeMillis()

    fun enqueue(c:Context,service:String,title:String,externalKey:String="",cover:String=""):String=synchronized(lock){
        val a=read(c,q(service))
        if(externalKey.isNotBlank()) for(i in 0 until a.length()){
            val x=a.getJSONObject(i)
            if(x.optString("externalKey")==externalKey && x.optString("state") in listOf("queued","running")) return@synchronized x.getString("id")
        }
        val id=UUID.randomUUID().toString()
        a.put(JSONObject().put("id",id).put("externalKey",externalKey).put("title",title.ifBlank{"제목 없음"}).put("cover",cover)
            .put("state","queued").put("message","다운로드 대기 중").put("createdAt",now()).put("updatedAt",now()))
        write(c,q(service),a); id
    }
    fun update(c:Context,service:String,id:String,state:String?=null,message:String?=null,progress:Int?=null)=synchronized(lock){
        val a=read(c,q(service))
        for(i in 0 until a.length()){val x=a.getJSONObject(i);if(x.optString("id")==id){
            if(state!=null)x.put("state",state);if(message!=null)x.put("message",message);if(progress!=null)x.put("progress",progress)
            x.put("updatedAt",now())
        }}
        write(c,q(service),a)
    }
    fun updateTitle(c:Context,service:String,id:String,title:String)=synchronized(lock){
        val a=read(c,q(service))
        for(i in 0 until a.length()){val x=a.getJSONObject(i);if(x.optString("id")==id)x.put("title",title)}
        write(c,q(service),a)
    }
    fun isCancelled(c:Context,service:String,id:String):Boolean=synchronized(lock){
        val a=read(c,q(service));(0 until a.length()).map{a.getJSONObject(it)}.firstOrNull{it.optString("id")==id}?.optBoolean("cancelRequested",false)?:true
    }
    fun cancel(c:Context,service:String,id:String)=synchronized(lock){
        val a=read(c,q(service));val out=JSONArray()
        for(i in 0 until a.length()){val x=a.getJSONObject(i);if(x.optString("id")!=id)out.put(x)}
        write(c,q(service),out)
    }
    fun fail(c:Context,service:String,id:String,message:String)=update(c,service,id,"failed",message)
    /** Marks work left in progress by a terminated process as actionable again. */
    fun failRunning(c:Context,service:String,message:String)=synchronized(lock){
        val a=read(c,q(service));var changed=false
        for(i in 0 until a.length()){val x=a.getJSONObject(i);if(x.optString("state")=="running"){
            x.put("state","failed").put("message",message).put("updatedAt",now());changed=true
        }}
        if(changed)write(c,q(service),a)
    }
    fun complete(c:Context,service:String,id:String,title:String,uri:String="",path:String="",name:String="")=synchronized(lock){
        val qa=read(c,q(service));val remain=JSONArray();var finalTitle=title;var cover=""
        for(i in 0 until qa.length()){val x=qa.getJSONObject(i);if(x.optString("id")==id){if(finalTitle.isBlank())finalTitle=x.optString("title");cover=x.optString("cover")}else remain.put(x)}
        write(c,q(service),remain)
        val sa=read(c,s(service))
        val rec=JSONObject().put("id",UUID.randomUUID().toString()).put("title",finalTitle.ifBlank{name.ifBlank{"다운로드 파일"}}).put("cover",cover)
            .put("uri",uri).put("path",path).put("name",name).put("completedAt",now())
        sa.put(rec);write(c,s(service),sa)
    }
    fun queue(c:Context,service:String):JSONArray=synchronized(lock){
        val src=read(c,q(service));val out=JSONArray()
        for(i in 0 until src.length()){val x=src.getJSONObject(i);if(!x.optBoolean("cancelRequested"))out.put(JSONObject(x.toString()))};out
    }
    fun saved(c:Context,service:String):JSONArray=synchronized(lock){
        val src=read(c,s(service));JSONArray((0 until src.length()).reversed().map{JSONObject(src.getJSONObject(it).toString())})
    }
    fun removeSaved(c:Context,service:String,id:String,deleteFile:Boolean):Boolean=synchronized(lock){
        val a=read(c,s(service));val out=JSONArray();var target:JSONObject?=null
        for(i in 0 until a.length()){val x=a.getJSONObject(i);if(x.optString("id")==id)target=x else out.put(x)}
        if(deleteFile && target!=null) deletePhysical(c,target!!)
        write(c,s(service),out);true
    }
    fun payload(c:Context,service:String,mode:String):JSONObject {
        val items=if(mode=="queue") queue(c,service) else saved(c,service)
        return JSONObject().put("mode",mode).put("items",items)
    }
    fun delete(c:Context,service:String,mode:String,id:String,deleteFile:Boolean=false):JSONObject {
        if(mode=="queue") cancel(c,service,id) else removeSaved(c,service,id,deleteFile)
        return payload(c,service,mode)
    }
    private fun deletePhysical(c:Context,x:JSONObject){
        val u=x.optString("uri")
        if(u.startsWith("content://")) runCatching{c.contentResolver.delete(Uri.parse(u),null,null)}
        else if(u.startsWith("file:")) runCatching{File(Uri.parse(u).path.orEmpty()).takeIf{it.exists()}?.delete()}
        val raw=x.optString("path")
        if(raw.isNotBlank()) runCatching{val f=if(raw.startsWith("/")) File(raw) else File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),raw.removePrefix("Download/"));if(f.exists())f.delete()}
    }
}

class DownloadCenterActivity: Activity() {
    private lateinit var service:String
    private lateinit var mode:String
    private lateinit var list:LinearLayout
    override fun onCreate(savedInstanceState:Bundle?){
        setTheme(if(GlobalConfig.isDark(this))R.style.Theme_CkReader_Cobalt else R.style.Theme_CkReader_Heyclicky)
        super.onCreate(savedInstanceState)
        service=intent.getStringExtra("service").orEmpty()
        mode=intent.getStringExtra("mode")?:"saved"
        title=if(mode=="queue")"다운로드큐" else "저장파일"
        val dark=GlobalConfig.isDark(this);val bg=if(dark)Color.rgb(15,25,40) else Color.rgb(247,249,252);val fg=if(dark)Color.WHITE else Color.rgb(25,35,50)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(16));setBackgroundColor(bg)}
        val head=TextView(this).apply{text=title;textSize=24f;setTextColor(fg);setPadding(0,0,0,dp(12))}
        list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val scroll=ScrollView(this).apply{addView(list,ViewGroup.LayoutParams(-1,-2))}
        root.addView(head,ViewGroup.LayoutParams(-1,-2));root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root);render()
    }
    override fun onResume(){super.onResume();render()}
    private fun render(){
        if(!::list.isInitialized)return
        list.removeAllViews();val dark=GlobalConfig.isDark(this);val fg=if(dark)Color.WHITE else Color.rgb(25,35,50);val sub=if(dark)Color.rgb(190,200,215) else Color.DKGRAY
        val data=if(mode=="queue")DownloadCenterStore.queue(this,service) else DownloadCenterStore.saved(this,service)
        if(data.length()==0){list.addView(TextView(this).apply{text=if(mode=="queue")"다운로드 큐가 비어 있습니다." else "저장된 파일 목록이 없습니다.";textSize=15f;setTextColor(sub);setPadding(0,dp(24),0,0)});return}
        for(i in 0 until data.length()){
            val x=data.getJSONObject(i)
            val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(12));background=android.graphics.drawable.GradientDrawable().apply{setColor(if(dark)Color.rgb(28,40,58) else Color.WHITE);cornerRadius=dp(10).toFloat()}}
            box.addView(TextView(this).apply{text=x.optString("title","제목 없음");textSize=16f;setTextColor(fg)})
            if(mode=="queue"){
                box.addView(TextView(this).apply{text=listOf(x.optString("state"),x.optString("message")).filter{it.isNotBlank()}.joinToString(" · ");textSize=13f;setTextColor(sub)})
                box.addView(Button(this).apply{text="삭제";isAllCaps=false;setOnClickListener{DownloadCenterStore.cancel(this@DownloadCenterActivity,service,x.getString("id"));render()}})
            }else{
                val loc=x.optString("name").ifBlank{x.optString("path").ifBlank{x.optString("uri")}}
                if(loc.isNotBlank())box.addView(TextView(this).apply{text=loc;textSize=12f;setTextColor(sub)})
                val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
                row.addView(Button(this).apply{text="삭제";isAllCaps=false;setOnClickListener{DownloadCenterStore.removeSaved(this@DownloadCenterActivity,service,x.getString("id"),false);render()}},LinearLayout.LayoutParams(0,-2,1f))
                row.addView(Button(this).apply{text="파일삭제";isAllCaps=false;setOnClickListener{DownloadCenterStore.removeSaved(this@DownloadCenterActivity,service,x.getString("id"),true);render()}},LinearLayout.LayoutParams(0,-2,1f))
                box.addView(row)
            }
            list.addView(box,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)})
        }
        ReaderDesign.finishButtons(list)
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    companion object{
        fun intent(c:Context,service:String,mode:String)=Intent(c,DownloadCenterActivity::class.java).putExtra("service",service).putExtra("mode",mode)
    }
}
