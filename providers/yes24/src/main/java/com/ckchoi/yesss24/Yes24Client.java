package com.ckchoi.yesss24;
import android.content.Context;
import android.util.Base64;
import org.json.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
public final class Yes24Client {
 static final String WEB="https://cremaclub.yes24.com", API="https://apivw.yes24.com/Service/Authentication/AuthService/";
 static final String UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36";
 private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
 private final SecureStore store;
 private final Context context;
 private JSONObject session=new JSONObject(), savedDevices=new JSONObject();
 private CremaAccounts accounts;
 public Yes24Client(Context context){this(context,new SecureStore(context));}
 public Yes24Client(Context context,boolean standaloneYes24){this(context,standaloneYes24?new SecureStore(context,"yes24.session.v1","yes24Vault"):new SecureStore(context));}
 private Yes24Client(Context context,SecureStore secureStore){
  this.context=context;store=secureStore;
  try {
   JSONObject saved=new JSONObject(store.load());
   accounts=new CremaAccounts(saved);
   savedDevices=saved.optJSONObject("known");if(savedDevices==null)savedDevices=new JSONObject();
   restore(accounts.snapshot());
  } catch(Exception e) {
   // Never overwrite an unreadable encrypted vault with an empty account list.
   throw new IllegalStateException("저장된 Crema 계정을 읽을 수 없습니다.",e);
  }
 }
 private void restore(JSONObject snapshot)throws Exception {
  session=snapshot.optJSONObject("session");if(session==null)session=new JSONObject();
  cookies.getCookieStore().removeAll();
  JSONArray a=snapshot.optJSONArray("cookies");
  if(a!=null)for(int i=0;i<a.length();i++){
   JSONObject j=a.getJSONObject(i);HttpCookie c=new HttpCookie(j.getString("name"),j.getString("value"));
   c.setDomain(j.getString("domain"));c.setPath(j.optString("path","/"));c.setSecure(j.optBoolean("secure",true));c.setVersion(j.optInt("version",0));
   cookies.getCookieStore().add(URI.create("https://"+c.getDomain().replaceFirst("^\\.","")),c);
  }
 }
 private void save()throws Exception {
  JSONArray a=new JSONArray();
  for(HttpCookie c:cookies.getCookieStore().getCookies())if(!c.hasExpired())
   a.put(new JSONObject().put("name",c.getName()).put("value",c.getValue()).put("domain",c.getDomain()).put("path",c.getPath()).put("version",c.getVersion()).put("secure",c.getSecure()));
  accounts.capture(session,a);store.save(accounts.serialize(savedDevices).toString());
 }
 public void switchAccount(String user)throws Exception {
  save();accounts.select(user);restore(accounts.snapshot());save();
 }
 public void removeAccount(String user)throws Exception {
  save();accounts.remove(user);savedDevices.remove(user);restore(accounts.snapshot());save();
 }
 private void log(String s){}
 public JSONObject backupVault()throws Exception {save();return accounts.serialize(savedDevices);}
 public JSONObject status()throws Exception {JSONObject result=new JSONObject().put("loggedIn",!session.optString("token").isEmpty()).put("userId",session.optString("userId")).put("userNo",session.optString("userNo")).put("device",session.optJSONObject("device")).put("devices",session.optJSONArray("devices")).put("accounts",accounts.summaries()).put("activeAccount",accounts.activeId());return result;}
 /** Export the existing CremaClub session; never invoke a second login stack. */
 public JSONObject downloadAccount(JSONObject options,boolean verifyShelf)throws Exception {
  requireLogin();JSONObject d=session.getJSONObject("device");
  JSONObject result=new JSONObject().put("authSource","crema").put("sessionToken",session.getString("token"))
   .put("userId",session.getString("userId")).put("userNo",session.getString("userNo"))
   .put("deviceId",d.getString("deviceId")).put("deviceName",d.optString("deviceName","SM-F976N"))
   .put("deviceType",d.optString("deviceType","Android")).put("osVersion",32)
   .put("pattern",options.optString("pattern",DownloadPattern.DEFAULT))
   .put("deleteAfterDownload",options.optBoolean("deleteAfterDownload",false));
  if(verifyShelf){
   String html=fetchPage("/BookClub/MyBookClub");
   Matcher m=Pattern.compile("(?i)(?<![a-z0-9_])[\"']?user_?no[\"']?\\s*[:=]\\s*[\"']?([0-9]+)").matcher(html);
   Set<String> numbers=new HashSet<>();while(m.find())numbers.add(m.group(1));
   if(numbers.size()!=1)throw new IOException("내서재 계정을 확인하지 못했습니다. 다시 로그인해주세요.");
   result.put("webUserNo",numbers.iterator().next());
  }
  JSONArray a=new JSONArray();for(HttpCookie c:cookies.getCookieStore().getCookies())if(!c.hasExpired())a.put(new JSONObject().put("name",c.getName()).put("value",c.getValue()).put("domain",c.getDomain()).put("path",c.getPath()).put("secure",c.getSecure()));
  result.put("cookies",a);save();return result;
 }
 public void logout()throws Exception {save();accounts.logout();restore(accounts.snapshot());save();}
 private String http(String url,String body,String contentType,String bearer,String ua)throws Exception {
  for(int redirects=0;redirects<8;redirects++){
   URI uri=URI.create(url);if(!uri.getScheme().equals("https")||!(uri.getHost().equals("yes24.com")||uri.getHost().endsWith(".yes24.com")))throw new IOException("허용되지 않은 주소입니다.");
   HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(25000);c.setRequestProperty("User-Agent",ua);c.setRequestProperty("Accept","*/*");c.setRequestProperty("Referer",WEB+"/BookClub/Main");for(Map.Entry<String,List<String>> h:cookies.get(uri,Collections.emptyMap()).entrySet())c.setRequestProperty(h.getKey(),String.join("; ",h.getValue()));
   if(uri.getHost().equals("m.yes24.com")){c.setRequestProperty("Referer",url);c.setRequestProperty("Origin","https://m.yes24.com");}
   if(bearer!=null&&!bearer.isEmpty())c.setRequestProperty("Authorization","Bearer "+bearer);
   if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type",contentType);try(OutputStream os=c.getOutputStream()){os.write(body.getBytes(StandardCharsets.UTF_8));}}
   int code=c.getResponseCode();cookies.put(uri,c.getHeaderFields());log((body==null?"GET ":"POST ")+uri.getPath()+" → HTTP "+code);
   if(code>=300&&code<400&&c.getHeaderField("Location")!=null){url=uri.resolve(c.getHeaderField("Location")).toString();body=null;bearer=null;c.disconnect();continue;}
   if(code>=400){c.disconnect();throw new IOException("서버 요청 실패 (HTTP "+code+")");}
   String type=c.getContentType();String charset="UTF-8";Matcher match=Pattern.compile("charset=([A-Za-z0-9_-]+)",Pattern.CASE_INSENSITIVE).matcher(type==null?"":type);if(match.find())charset=match.group(1);
   ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>16000000)throw new IOException("응답이 너무 큽니다.");}}finally{c.disconnect();}return out.toString(charset);
  }throw new IOException("로그인 이동 횟수를 초과했습니다.");
 }
 private JSONObject post(String url,JSONObject data,String token,boolean legacy)throws Exception {String text=http(url,data.toString()+(legacy?"\n\0":""),"application/json; charset=utf-8",token,legacy?"READON_HTTP":"okhttp/4.12.0");JSONObject r=new JSONObject(text.replace("\0","").trim());log("API 결과 코드: "+r.optInt("resultCode",0));return r;}
 static String enc(String v)throws Exception{return URLEncoder.encode(v,"UTF-8");}
 private String form(Map<String,String> data)throws Exception {StringJoiner j=new StringJoiner("&");for(Map.Entry<String,String> e:data.entrySet())j.add(enc(e.getKey())+"="+enc(e.getValue()));return j.toString();}
 private JSONObject trace(JSONObject d)throws Exception {boolean pc="PC_YES24".equals(d.optString("deviceType"));return new JSONObject().put("applicationVersion",pc?"5.2.2.1095":"3.4.36").put("deviceModel",pc?"PC_YES24EBOOK":d.optString("deviceName","SM-F976N")).put("osVersion",pc?"Windows 8.0":32).put("packageName",pc?"PC_YES24EBOOK":"com.yes24.ebook.fourth");}
 private JSONObject info(JSONObject d)throws Exception{return new JSONObject().put("deviceId",d.getString("deviceId")).put("deviceName",d.optString("deviceName","SM-F976N")).put("deviceType",d.optString("deviceType","Android"));}
 private JSONObject newDevice()throws Exception {
  byte[] key={(byte)235,(byte)145,(byte)191,(byte)157,(byte)141,(byte)236,(byte)237,(byte)164,(byte)139,(byte)235,(byte)236,(byte)150,(byte)158,(byte)184,(byte)236,(byte)235,(byte)160,(byte)148,(byte)149,(byte)148,(byte)237,(byte)236,(byte)156,(byte)149,(byte)161,(byte)140,(byte)235,(byte)236,(byte)151,(byte)140,(byte)160,(byte)235};
  byte[] iv={(byte)237,(byte)236,(byte)150,(byte)160,(byte)153,(byte)179,(byte)184,(byte)148,(byte)180,(byte)149,(byte)160,(byte)156,(byte)235,(byte)136,(byte)152,(byte)164};
  Cipher c=Cipher.getInstance("AES/CBC/PKCS5Padding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));String uuid=UUID.randomUUID().toString();uuid=uuid.substring(0,14)+"5"+uuid.substring(15);byte[] crypt=c.doFinal(uuid.getBytes(StandardCharsets.UTF_8));byte[] wrap=new byte[crypt.length+1];wrap[0]=12;System.arraycopy(crypt,0,wrap,1,crypt.length);return new JSONObject().put("deviceId",Base64.encodeToString(wrap,Base64.NO_WRAP)).put("deviceName",android.os.Build.MODEL).put("deviceType","Android");
 }
 public void login(String user,String password)throws Exception {
  save();String previous=accounts.activeId();
  try {loginCurrent(user,password);}
  catch(Exception e){
   if(session.optString("token").isEmpty()){accounts.select(previous);restore(accounts.snapshot());save();}
   throw e;
  }
 }
 private void loginCurrent(String user,String password)throws Exception {
  if(user.trim().isEmpty()||password.isEmpty())throw new IOException("아이디와 비밀번호를 입력해주세요.");
  session=new JSONObject();cookies.getCookieStore().removeAll();accounts.select("");save();
  String loginUrl="https://m.yes24.com/Momo/Templates/FTLogin.aspx";
  Document login=Jsoup.parse(http(loginUrl,null,null,null,UA));Map<String,String> f=new LinkedHashMap<>();for(Element e:login.select("input[name]"))f.put(e.attr("name"),e.attr("value"));String loginToken=f.getOrDefault("hdfLoginToken","");if(loginToken.isEmpty())throw new IOException("로그인 페이지가 변경되어 인증할 수 없습니다.");
  f.put("SMemberID",user);f.put("SMemberPassword",password);f.put("AutoLogin","1");f.put("LoginKeep","Y");f.put("hdfLoginHash",Base64.encodeToString((System.currentTimeMillis()+"|"+loginToken).getBytes(StandardCharsets.UTF_8),2));http(loginUrl,form(f),"application/x-www-form-urlencoded",null,UA);f.clear();
  JSONObject webToken=new JSONObject(http(WEB+"/BookClub/GetAuthToken","","application/x-www-form-urlencoded",null,UA));String token=webToken.optString("authToken","");if(token.isEmpty()||token.equals("null"))throw new IOException("로그인 실패: 아이디·비밀번호 또는 추가 인증 필요 여부를 확인해주세요.");
  JSONObject d=savedDevices.optJSONObject(user);
  // One-time migration of the existing device explicitly supplied by the owner. No credentials are bundled.
  if(d==null&&user.equals("watsonxai"))d=new JSONObject().put("deviceId","DN2Ubn0ZUYAAyqhqGUqlmYq/3KKLDsdOufdctDUTT9xpBk7QmiDJRWh9BNUrBHlkgw==").put("deviceName","SM-F976N").put("deviceType","Android");
  if(d==null)d=newDevice();savedDevices.put(user,d);save();
  JSONObject response=post("https://api.yes24.com/digital/user/eBookAPPLogin",new JSONObject().put("deviceInfo",info(d)).put("encDevice","1").put("password",password).put("trace",trace(d)).put("username",user),token,false);
  JSONObject authenticated=SessionCodec.parse(response,user);session=authenticated;savedDevices.put(user,session.getJSONObject("device"));save();
  try{refreshDevices();}catch(Exception e){session.put("deviceWarning","기기 전체 조회 실패. 인증된 기기를 사용 중입니다. 설정에서 다시 조회해주세요.");save();throw new IOException("로그인은 성공했지만 기기 전체 조회에 실패했습니다. 다시 조회해주세요.");}
 }
 public void refreshDevices()throws Exception {
  requireLogin();JSONObject d=session.getJSONObject("device");JSONObject res=post(API+"RequestUserDeviceCheck",new JSONObject().put("storeId","keph_yes24").put("userId",session.getString("userId")).put("userNo",session.getString("userNo")).put("deviceInfo",info(d)).put("trace",trace(d)),session.getString("token"),true);
  if(res.optInt("resultCode")!=100)throw new IOException("기기 조회 실패 ("+res.optInt("resultCode")+"). 다시 로그인해주세요.");JSONArray list=SessionCodec.devices(res);if(list.length()==0)throw new IOException("서버가 빈 기기 목록을 반환했습니다. 다시 로그인하여 기기를 인증해주세요.");session.put("devices",list).put("device",info(list.getJSONObject(0)));savedDevices.put(session.getString("userId"),session.getJSONObject("device"));save();
 }
 void requireLogin()throws IOException{if(session.optString("token").isEmpty())throw new IOException("설정에서 먼저 로그인해주세요.");}
 public String fetchPage(String path)throws Exception {
  if(!path.matches("(?i)^/BookClub/(Main|GetPopularEbookList|LiveTrendBooks|Detail/[0-9]+|Search|MyBookclubGoodsList|MyBookClub)(\\?.*)?$"))throw new IOException("지원하지 않는 요청입니다.");
  if(path.toLowerCase(Locale.ROOT).contains("mybook"))requireLogin();String text=http(WEB+path,null,null,null,UA);if(text.contains("id=\"loginFormWrap\""))throw new IOException("웹 로그인 세션이 만료되었습니다. 다시 로그인해주세요.");return text;
 }
 public String fetchYes24Ebook(String query)throws Exception {
  String value=query==null?"":query.trim();
  if(value.length()>120)throw new IOException("검색어는 120자 이하로 입력해주세요.");
  String url=value.isEmpty()?"https://www.yes24.com/Mall/Main/eBook/017?CategoryNumber=017":
   "https://www.yes24.com/Product/Search?domain=EBOOK&query="+enc(value);
  return http(url,null,null,null,UA);
 }
 public String fetchYes24EbookDetail(String goodsId)throws Exception {
  if(goodsId==null||!goodsId.matches("[0-9]+"))throw new IOException("잘못된 상품 번호입니다.");
  return http("https://www.yes24.com/Product/Goods/"+goodsId,null,null,null,UA);
 }
 public JSONObject add(String id)throws Exception {
  requireLogin();if(!id.matches("[0-9]+"))throw new IOException("잘못된 도서 번호입니다.");JSONObject r=new JSONObject(http(WEB+"/BookClub/UpdateMyBookClubGoodsList","goodsNoList="+id+"&rentYn=Y","application/x-www-form-urlencoded",null,UA));
  boolean ok="SUCCESS".equals(r.optString("returnMsg"))&&(r.optInt("resultCode")==100||r.optInt("resultCode")==101)&&r.optInt("successCnt")>0;
  JSONObject api=r.optJSONObject("apiResult");JSONArray failures=api==null?null:api.optJSONArray("ResultFailList");boolean exists=failures!=null&&failures.length()==1&&failures.getJSONObject(0).optInt("ResultCode")==-103;
  if(!ok&&!exists)throw new IOException("서재 추가 실패 ("+r.optString("returnMsg","응답 오류")+"). 이용권과 로그인 상태를 확인해주세요.");return new JSONObject().put("added",true).put("alreadyExists",exists);
 }
 public JSONObject remove(String goodsId)throws Exception {
  requireLogin();if(!goodsId.matches("[0-9]+"))throw new IOException("잘못된 도서 번호입니다.");
  JSONObject r=new JSONObject(http(WEB+"/BookClub/UpdateMyBookClubGoodsList","goodsNoList="+enc(goodsId)+"&rentYn=N","application/x-www-form-urlencoded",null,UA));
  if(!"SUCCESS".equals(r.optString("returnMsg"))||!(r.optInt("resultCode")==100||r.optInt("resultCode")==101)||r.optInt("successCnt")<1)throw new IOException("내서재에서 삭제하지 못했습니다. 로그인과 이용권을 확인해주세요.");
  return new JSONObject().put("removed",true);
 }
 private JSONObject normalizePurchase(JSONObject x,int order)throws Exception {
  String thumb=x.optString("thumnailUrl");if(thumb.isEmpty())thumb=x.optString("thumbnailUrl");if(thumb.isEmpty())thumb=x.optString("thumbnail");if(thumb.isEmpty())thumb=x.optString("coverUrl");if(thumb.isEmpty())thumb=x.optString("imageUrl");
  return new JSONObject().put("title",x.optString("title")).put("author_name",x.optString("authorName")).put("publisher",x.optString("publishingName"))
   .put("order_date",x.optString("orderDate")).put("rent_start_date",x.optString("rentStartDate")).put("rent_end_date",x.optString("rentEndDate"))
   .put("ebook_code",x.optInt("ebookCode")).put("ebook_id",x.optString("ebookId")).put("epub_id",x.optInt("epubId")).put("contents_type",x.optString("contentsType"))
   .put("thumbnail_url",thumb).put("tts",x.optInt("tts")).put("contents_mod_date",x.optString("contentsModDate")).put("order_detail_seq",x.optInt("orderDetailSeq"))
   .put("order_seq",x.optInt("orderSeq")).put("purchase_list_seq",x.optInt("purchaseListSeq")).put("sale_type",x.optInt("saleType")).put("drm_type",x.optInt("drmType"))
   .put("product_code",x.optInt("productCode")).put("product_type",x.optInt("productType")).put("seller_order_cd",x.optString("sellerOrderCd"))
   .put("rent_period",x.optInt("rentPeriod")).put("file_size",x.optInt("fileSize")).put("sort_order",order);
 }
 private JSONArray apiList(String path,String version)throws Exception {
  requireLogin();JSONObject d=session.getJSONObject("device");JSONObject payload=new JSONObject().put("deviceInfo",info(d)).put("storeId","keph_yes24").put("trace",trace(d)).put("userId",session.optString("userId")).put("userNo",session.optString("userNo")).put("version",version);
  JSONObject r=post("https://apivw.yes24.com"+path,payload,session.optString("token"),true);if(r.optInt("resultCode")!=100)throw new IOException(r.optString("resultMessage","YES24 서재 조회에 실패했습니다."));
  JSONArray source=r.optJSONArray("ebookList");if(source==null)source=new JSONArray();JSONArray out=new JSONArray();for(int i=0;i<source.length();i++){JSONObject x=source.optJSONObject(i);if(x!=null){JSONObject n=normalizePurchase(x,i+1);if(n.optInt("ebook_code")>0)out.put(n);}}return out;
 }
 public JSONArray purchaseList()throws Exception {return apiList("/Service/Product/ProductService/GetPurchaseList","3");}
 public JSONArray bookclubList()throws Exception {return apiList("/Service/Product/ProductService.svc/GetUserFlatRateList","3");}
 public JSONObject purchases()throws Exception {JSONArray a=purchaseList();return new JSONObject().put("books",a).put("total_count",a.length());}

 private byte[] genericGetBytes(String url,String userAgent,int max)throws Exception {
  URL u=new URL(url);String scheme=u.getProtocol().toLowerCase(Locale.ROOT);if(!scheme.equals("https")&&!scheme.equals("http"))throw new IOException("지원하지 않는 다운로드 주소입니다.");
  HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(20000);c.setReadTimeout(120000);c.setRequestProperty("User-Agent",userAgent);c.setRequestProperty("Accept","*/*");
  int code=c.getResponseCode();if(code>=400){c.disconnect();throw new IOException("다운로드 요청 실패 (HTTP "+code+")");}
  ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>max)throw new IOException("다운로드 파일이 너무 큽니다.");}}finally{c.disconnect();}return out.toByteArray();
 }
 private String genericGetText(String url,String userAgent)throws Exception {return new String(genericGetBytes(url,userAgent,8*1024*1024),StandardCharsets.UTF_8);}
 private void genericDownloadToFile(String url,String userAgent,File target,long max)throws Exception {
  URL u=new URL(url);String scheme=u.getProtocol().toLowerCase(Locale.ROOT);if(!scheme.equals("https")&&!scheme.equals("http"))throw new IOException("지원하지 않는 다운로드 주소입니다.");
  HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(20000);c.setReadTimeout(120000);c.setRequestProperty("User-Agent",userAgent);c.setRequestProperty("Accept","*/*");
  try {
   int code=c.getResponseCode();if(code>=400)throw new IOException("다운로드 요청 실패 (HTTP "+code+")");
   long length=0;try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(target)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){length+=n;if(length>max)throw new IOException("다운로드 파일이 너무 큽니다.");out.write(b,0,n);}out.getFD().sync();}
   if(length==0)throw new IOException("다운로드 파일이 비어 있습니다.");
  }catch(Exception e){target.delete();throw e;}finally{c.disconnect();}
 }
 private JSONObject purchaseByCode(String code,boolean club)throws Exception {if(!code.matches("[0-9]+"))throw new IOException("올바른 도서 번호가 아닙니다.");JSONArray a=club?bookclubList():purchaseList();for(int i=0;i<a.length();i++){JSONObject x=a.getJSONObject(i);if(String.valueOf(x.optInt("ebook_code")).equals(code))return x;}throw new IOException("다운로드할 도서를 찾지 못했습니다. 목록을 새로고침해주세요.");}
 private String cremaEbookCode(String goodsId)throws Exception {
  if(!goodsId.matches("[0-9]+"))throw new IOException("잘못된 상품 번호입니다.");String html=fetchPage("/BookClub/Detail/"+goodsId);
  Matcher gm=Pattern.compile("\\bvar\\s+g_GoodsNo\\s*=\\s*[\\\"']([0-9]+)[\\\"']\\s*;").matcher(html);Matcher em=Pattern.compile("\\bvar\\s+g_EbookCode\\s*=\\s*[\\\"']([0-9]+)[\\\"']\\s*;").matcher(html);Set<String> gs=new HashSet<>(),es=new HashSet<>();while(gm.find())gs.add(gm.group(1));while(em.find())es.add(em.group(1));
  if(gs.size()!=1||!gs.contains(goodsId)||es.size()!=1)throw new IOException("상품 상세에서 다운로드 도서번호를 확인하지 못했습니다.");return es.iterator().next();
 }
 private File downloadPurchaseInternal(JSONObject book,File work,java.util.function.Consumer<String> status,String bookId)throws Exception {
  requireLogin();status.accept("YES24 기기 인증 중…");refreshDevices();JSONObject d=session.getJSONObject("device");JSONObject di=info(d);
  JSONObject payload=new JSONObject().put("contentsType",book.optString("contents_type")).put("deviceInfo",di).put("drmType",book.optInt("drm_type")).put("ebookCode",book.optInt("ebook_code"))
   .put("orderDetailSeq",book.optInt("order_detail_seq")).put("orderSeq",book.optInt("order_seq")).put("productCode",book.optInt("product_code")).put("purchaseListSeq",book.optInt("purchase_list_seq"))
   .put("rentEndDate",book.optString("rent_end_date")).put("rentPeriod",book.optInt("rent_period")).put("rentStartDate",book.optString("rent_start_date")).put("saleType",book.optInt("sale_type"))
   .put("sellerOrderCd",book.optString("seller_order_cd")).put("storeId","keph_yes24").put("trace",new JSONObject().put("applicationVersion","5.2.2.1095").put("callId","IDC_UD_BTN_DOWNALL_BUYLIST_150").put("deviceModel","PC_YES24EBOOK").put("menuId","FRONT_BOOKCLUB_buyListNormal").put("osVersion","Windows 8.0").put("packageName","PC_YES24EBOOK")).put("userNo",session.optString("userNo")).put("version","3");
  JSONObject drm=post("https://apivw.yes24.com/Service/License/LicenseService/RequestDrmOrder",payload,session.optString("token"),true);if(drm.optInt("resultCode")!=100)throw new IOException(drm.optString("resultMessage","YES24 DRM 주문 요청에 실패했습니다."));String license=drm.optString("license");if(license.isEmpty())throw new IOException("YES24 라이선스 정보를 받지 못했습니다.");
  String ver=Yes24Drm.INSTANCE.licenseValue(license,"VER",true),uuid=Yes24Drm.INSTANCE.licenseValue(license,"UUID",true),ord=Yes24Drm.INSTANCE.licenseValue(license,"DRM_ORD",true),down=Yes24Drm.INSTANCE.html(Yes24Drm.INSTANCE.licenseValue(license,"DOWN_URL",true)),auth=Yes24Drm.INSTANCE.html(Yes24Drm.INSTANCE.licenseValue(license,"AUTH_URL",true)),type=Yes24Drm.INSTANCE.licenseValue(license,"F_TYPE",true);if(type.isEmpty())type=book.optString("contents_type");if(down.isEmpty()||auth.isEmpty())throw new IOException("YES24 다운로드 또는 인증 URL이 비어 있습니다.");
  status.accept("YES24 원본 다운로드 중…");File raw=File.createTempFile("yes24-raw-",".bin",work);genericDownloadToFile(down,"WinInet Progress",raw,1024L*1024L*1024L);
  try {
  String device=Yes24Drm.INSTANCE.decodeDeviceId(di.optString("deviceId"));kotlin.Pair<byte[],byte[]> pair=Yes24Drm.INSTANCE.queryKeyIv(ver);String plain="VER="+ver+"&UDID="+device+"&UUID="+uuid+"&DRM_ORD="+ord+"&tk="+Yes24Drm.INSTANCE.randomTk();String query=Yes24Drm.INSTANCE.encryptBase64(plain.getBytes(StandardCharsets.US_ASCII),pair.getFirst(),pair.getSecond());String reply=genericGetText(auth+(auth.contains("?")?"&":"?")+"QUERY="+enc(query),"Windows 10.0 UNDRNCLIENT 1.0.2.1003");String err=Yes24Drm.INSTANCE.licenseValue(reply,"ERR_CD",false);if(!"0".equals(err))throw new IOException("YES24 라이선스 인증 실패: "+err+" "+Yes24Drm.INSTANCE.licenseValue(reply,"ERR_MSG",false));String ck=Yes24Drm.INSTANCE.licenseValue(reply,"L_DATA",false);if(ck.isEmpty())throw new IOException("YES24 라이선스 응답에 L_DATA가 없습니다.");byte[] key=Yes24Drm.INSTANCE.deriveBookKey(ck,device);
  status.accept("YES24 DRM 복호화 중…");String upper=type.toUpperCase(Locale.ROOT),ext=upper.equals("PDF")?"pdf":(upper.equals("COMIC")||upper.equals("EPUB_COMIC"))?"zip":"epub";String stem=Yes24Drm.INSTANCE.safeName(book.optString("title","제목 없음"))+"_"+Yes24Drm.INSTANCE.safeName(book.optString("author_name","저자 미상"))+"_"+Yes24Drm.INSTANCE.safeName(bookId);File output=new File(work,stem+"."+ext);try{Yes24Drm.INSTANCE.exportBook(raw,output,type,key);return output;}catch(Exception e){output.delete();throw e;}
  } finally { raw.delete(); }
 }
 public String downloadPurchase(String code,DownloadPublisher publisher)throws Exception {File work=new File(context.getCacheDir(),"yes24-native");work.mkdirs();JSONObject book=purchaseByCode(code,false);publisher.setBookTitle(book.optString("title"));publisher.setAuthor(book.optString("author_name"));File f=downloadPurchaseInternal(book,work,publisher::status,code);try{publisher.status(com.ckchoi.elibrary.common.GlobalConfig.INSTANCE.relativePath(context,"yes24")+"에 저장 중…");publisher.publish(f.getAbsolutePath());return "다운로드 완료 · "+com.ckchoi.elibrary.common.GlobalConfig.INSTANCE.relativePath(context,"yes24");}finally{f.delete();}}
 public String downloadCrema(String goodsId,DownloadPublisher publisher)throws Exception {String code=cremaEbookCode(goodsId);File work=new File(context.getCacheDir(),"yes24-native");work.mkdirs();JSONObject book=purchaseByCode(code,true);publisher.setBookTitle(book.optString("title"));publisher.setAuthor(book.optString("author_name"));File f=downloadPurchaseInternal(book,work,publisher::status,goodsId);try{publisher.status(com.ckchoi.elibrary.common.GlobalConfig.INSTANCE.relativePath(context,"yes24")+"에 저장 중…");publisher.publish(f.getAbsolutePath());}finally{f.delete();}String result="다운로드 완료 · "+com.ckchoi.elibrary.common.GlobalConfig.INSTANCE.relativePath(context,"yes24");JSONObject options=new Store(context).account("account");if(options.optBoolean("deleteAfterDownload",false)){try{remove(goodsId);result+=" · 내서재 삭제 완료";}catch(Exception e){result+=" · 내서재 삭제 실패(도서는 유지됩니다)";}}return result;}
 public File prepareReader(String id,boolean club)throws Exception {
  String code=club?cremaEbookCode(id):id;
  JSONObject book=purchaseByCode(code,club);
  File work=new File(context.getCacheDir(),"yes24-native");if(!work.isDirectory()&&!work.mkdirs())throw new IOException("임시 저장소를 열 수 없습니다.");
  File result=downloadPurchaseInternal(book,work,message->{},id);
  if(!result.getName().toLowerCase(Locale.ROOT).matches(".*\\.(epub|pdf)$")){result.delete();throw new IOException("이 도서 형식은 앱 뷰어에서 지원하지 않습니다.");}
  File readerDir=new File(context.getCacheDir(),"yes24-reader");if(!readerDir.isDirectory()&&!readerDir.mkdirs()){result.delete();throw new IOException("뷰어 저장소를 열 수 없습니다.");}
  File target=File.createTempFile("book-","."+result.getName().substring(result.getName().lastIndexOf('.')+1),readerDir);
  try(InputStream in=new FileInputStream(result);FileOutputStream out=new FileOutputStream(target)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);out.getFD().sync();}
  catch(Exception e){target.delete();throw e;}finally{result.delete();}
  return target;
 }
 public static JSONArray officialLibraries()throws Exception {
  URL u=new URL("https://svc.k-epub.com/Service/CustomerService/CustomerService.svc/GetLibraryList");HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=utf-8");c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","Yesss24-Android");try(OutputStream os=c.getOutputStream()){os.write(new byte[0]);}if(c.getResponseCode()>=400)throw new IOException("공식 전자도서관 목록 조회 실패 (HTTP "+c.getResponseCode()+")");String text;try(InputStream in=c.getInputStream()){text=new String(in.readAllBytes(),StandardCharsets.UTF_8);}finally{c.disconnect();}Object v=new JSONTokener(text.trim()).nextValue();if(v instanceof JSONObject&&((JSONObject)v).has("d"))v=((JSONObject)v).get("d");if(v instanceof String)v=new JSONTokener(((String)v).trim()).nextValue();JSONArray src=null;if(v instanceof JSONArray)src=(JSONArray)v;else if(v instanceof JSONObject){JSONObject o=(JSONObject)v;Iterator<String> it=o.keys();while(it.hasNext()){Object x=o.opt(it.next());if(x instanceof JSONArray){src=(JSONArray)x;break;}}if(src==null){src=new JSONArray();src.put(o);}}if(src==null)src=new JSONArray();JSONArray out=new JSONArray();Set<String> seen=new HashSet<>();for(int i=0;i<src.length();i++){JSONObject x=src.optJSONObject(i);if(x==null)continue;String name=x.optString("lib_nm").trim(),url=x.optString("lib_url").trim();try{URI uri=URI.create(url);if(name.isEmpty()||!("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null)continue;String clean=new URI(uri.getScheme().toLowerCase(Locale.ROOT),null,uri.getHost(),uri.getPort(),uri.getPath()==null?"":uri.getPath().replaceAll("/+$",""),null,null).toString();String key=name+"|"+clean.toLowerCase(Locale.ROOT);if(seen.add(key))out.put(new JSONObject().put("name",name).put("url",clean));}catch(Exception ignore){}}if(out.length()==0)throw new IOException("공식 전자도서관 목록이 비어 있습니다.");return out;
 }

}
