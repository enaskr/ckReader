package com.ckchoi.yesss24

/** Range highlighting never invokes WebView's find-in-page scrolling. */
internal object EpubSpeechHighlight {
 fun clear()="""(function(){if(window.__ckReaderSpeech)window.__ckReaderSpeech.clear();})()"""
 /** Measure a spoken phrase's spread without scrolling or painting a hidden page. */
 fun locate(start:Int,end:Int,url:String):String=script(start,end,url).replace(
  "window.__ckReaderSpeech.highlight(","window.__ckReaderSpeech.locate(")
 fun script(start:Int,end:Int,url:String):String="""(function(){try{
  if(location.href!==${org.json.JSONObject.quote(url)})return null;
  ${EpubSpeechSnapshot.documentScript}
  return window.__ckReaderSpeech.highlight($start,$end);
 }catch(e){return null;}})()"""
 fun word(start:Int,end:Int,url:String):String="""(function(){try{
  if(location.href!==${org.json.JSONObject.quote(url)})return null;
  ${EpubSpeechSnapshot.documentScript}
  return window.__ckReaderSpeech.word($start,$end);
 }catch(e){return null;}})()"""
}
