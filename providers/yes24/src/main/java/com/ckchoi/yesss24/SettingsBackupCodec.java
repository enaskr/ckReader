package com.ckchoi.yesss24;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.json.*;

/** Portable settings; credentials are deliberately included in the user-owned JSON. */
public final class SettingsBackupCodec {
 public static final int MAX_BYTES = 2 * 1024 * 1024;
 public static final String APP = "com.ckchoi.yesss24";
 private static IOException invalid(){return new IOException("올바른 Yesss24 설정 백업 파일이 아닙니다.");}
 private static String string(JSONObject obj,String key,int max,boolean empty)throws Exception {
  Object value=obj.get(key);
  if(!(value instanceof String))throw invalid();
  String s=(String)value;
  if((!empty&&s.isEmpty())||s.length()>max||s.chars().anyMatch(c->c<32))throw invalid();
  return s;
 }
 private static boolean bool(JSONObject obj,String key)throws Exception {Object v=obj.get(key);if(!(v instanceof Boolean))throw invalid();return (Boolean)v;}
 private static JSONArray array(JSONObject obj,String key,int max)throws Exception {JSONArray a=obj.getJSONArray(key);if(a.length()>max)throw invalid();return a;}
 private static void device(JSONObject d)throws Exception {
  string(d,"deviceId",8192,false);
  for(String key:new String[]{"deviceName","deviceType"})if(d.has(key))string(d,key,512,true);
 }
 private static boolean yes24Host(String domain){String h=domain.startsWith(".")?domain.substring(1):domain;return h.equals("yes24.com")||h.endsWith(".yes24.com");}
 public static JSONObject validate(JSONObject raw)throws Exception {
  if(raw.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IOException("설정 백업 파일은 2MB 이하여야 합니다.");
  if(!APP.equals(raw.optString("app")))throw invalid();
  Object version=raw.get("schemaVersion");
  if(!(version instanceof Number)||((Number)version).doubleValue()!=1)throw new IOException("지원하지 않는 설정 백업 버전입니다.");
  JSONObject s=raw.getJSONObject("settings");
  String pattern=DownloadPattern.validate(string(s,"filenamePattern",180,false));
  String selected=string(s,"selectedLibrary",200,true);
  JSONArray libraries=array(s,"libraries",50),cleanLibraries=new JSONArray();Set<String> ids=new HashSet<>();
  for(int i=0;i<libraries.length();i++) {
   JSONObject l=libraries.getJSONObject(i);String id=string(l,"id",200,false);
   if(!ids.add(id))throw invalid();
   String url=string(l,"url",2048,false);URI uri=new URI(url);
   if(!("https".equals(uri.getScheme())||"http".equals(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null)throw invalid();
   String user=string(l,"user",256,false),mac=string(l,"mac",17,false);
   if(!user.matches("[A-Za-z0-9@._-]+")||!mac.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))throw invalid();
   cleanLibraries.put(new JSONObject().put("id",id).put("name",string(l,"name",256,false)).put("url",url).put("user",user).put("mac",mac));
  }
  if(!selected.isEmpty()&&!ids.contains(selected))throw invalid();
  JSONObject vault=s.getJSONObject("cremaVault"),profiles=vault.getJSONObject("accounts"),known=vault.getJSONObject("known");
  if(profiles.length()>100||known.length()>200)throw invalid();
  String active=string(vault,"activeAccount",256,true);
  if(!active.isEmpty()&&!profiles.has(active))throw invalid();
  for(java.util.Iterator<String> keys=profiles.keys();keys.hasNext();) {
   String id=keys.next();JSONObject snapshot=profiles.getJSONObject(id),session=snapshot.getJSONObject("session");
   if(!id.equals(string(session,"userId",256,false)))throw invalid();
   String token=session.has("token")?string(session,"token",32768,true):"";
   if(!token.isEmpty()) {
    if(!string(session,"userNo",80,false).matches("[0-9]+"))throw invalid();
    device(session.getJSONObject("device"));
   }
   if(session.has("devices")&&!session.isNull("devices")){
    JSONArray devices=array(session,"devices",100);for(int j=0;j<devices.length();j++)device(devices.getJSONObject(j));
   }
   JSONArray cookies=array(snapshot,"cookies",500);
   for(int j=0;j<cookies.length();j++) {
    JSONObject c=cookies.getJSONObject(j);string(c,"name",256,false);string(c,"value",32768,true);
    String domain=string(c,"domain",256,false);
    if(!yes24Host(domain)||domain.contains("/")||domain.contains(":"))throw invalid();
    if(!string(c,"path",2048,false).startsWith("/"))throw invalid();
   }
  }
  for(java.util.Iterator<String> keys=known.keys();keys.hasNext();)device(known.getJSONObject(keys.next()));
  JSONObject yes24Vault=s.optJSONObject("yes24Vault");if(yes24Vault==null)yes24Vault=new JSONObject().put("activeAccount","").put("accounts",new JSONObject()).put("known",new JSONObject());
  JSONObject yes24Profiles=yes24Vault.getJSONObject("accounts"),yes24Known=yes24Vault.getJSONObject("known");
  if(yes24Profiles.length()>100||yes24Known.length()>200)throw invalid();
  String yes24Active=string(yes24Vault,"activeAccount",256,true);if(!yes24Active.isEmpty()&&!yes24Profiles.has(yes24Active))throw invalid();
  for(java.util.Iterator<String> keys=yes24Profiles.keys();keys.hasNext();) {String id=keys.next();JSONObject snapshot=yes24Profiles.getJSONObject(id),session=snapshot.getJSONObject("session");if(!id.equals(string(session,"userId",256,false)))throw invalid();if(session.has("token")&&!string(session,"token",32768,true).isEmpty()){if(!string(session,"userNo",80,false).matches("[0-9]+"))throw invalid();device(session.getJSONObject("device"));}array(snapshot,"cookies",500);}
  for(java.util.Iterator<String> keys=yes24Known.keys();keys.hasNext();)device(yes24Known.getJSONObject(keys.next()));
  JSONObject options=s.getJSONObject("cremaOptions"),cleanOptions=new JSONObject();
  if(options.length()>200)throw invalid();
  for(java.util.Iterator<String> keys=options.keys();keys.hasNext();) {
   String id=keys.next();if(!id.matches("[0-9]+"))throw invalid();
   cleanOptions.put(id,new JSONObject().put("deleteAfterDownload",bool(options.getJSONObject(id),"deleteAfterDownload")));
  }
  // Only known settings cross into preferences; unrelated backup fields never become file paths or preferences.
  return new JSONObject().put("app",APP).put("schemaVersion",1).put("createdAt",raw.optString("createdAt",""))
   .put("settings",new JSONObject().put("filenamePattern",pattern).put("selectedLibrary",selected)
    .put("libraries",cleanLibraries).put("cremaVault",new JSONObject(vault.toString())).put("yes24Vault",new JSONObject(yes24Vault.toString())).put("cremaOptions",cleanOptions));
 }
 public static JSONObject parse(String json)throws Exception {
  if(json.startsWith("\ufeff"))json=json.substring(1);
  if(json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IOException("설정 백업 파일은 2MB 이하여야 합니다.");
  int depth=0;boolean quoted=false,escaped=false;
  for(int i=0;i<json.length();i++){
   char c=json.charAt(i);
   if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}
   else if(c=='"')quoted=true;
   else if(c=='{'||c=='['){if(++depth>32)throw invalid();}
   else if(c=='}'||c==']')depth--;
  }
  try {
   JSONTokener tokener=new JSONTokener(json);Object value=tokener.nextValue();
   if(!(value instanceof JSONObject)||tokener.nextClean()!=0)throw invalid();
   return validate((JSONObject)value);
  }catch(IOException e){throw e;}catch(Exception e){throw invalid();}
 }
}
