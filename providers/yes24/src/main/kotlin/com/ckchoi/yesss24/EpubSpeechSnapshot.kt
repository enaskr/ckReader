package com.ckchoi.yesss24

/** One DOM text representation for visible starts and held sentence offsets. */
internal object EpubSpeechSnapshot {
 // Both extraction and highlighting use this exact node map, including inline text and block separators.
 val documentScript="""
  if(!window.__ckReaderSpeech){window.__ckReaderSpeech=(function(){
   var parts=[],nodes=[],length=0,lastBlock=null,activeRange=null,wordRange=null,overlay=null,wordOverlay=null;
   var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT),node;
   while(node=walker.nextNode()){
    var element=node.parentElement;if(!element||element.closest('script,style,noscript,[hidden],[aria-hidden="true"],[data-ckreader-speech]'))continue;
    var style=getComputedStyle(element);if(style.display==='none'||style.visibility==='hidden'||!node.nodeValue.length)continue;
    var range=document.createRange();range.selectNodeContents(node);if(!range.getClientRects().length)continue;
    var block=element;while(block.parentElement&&block!==document.body&&getComputedStyle(block).display==='inline')block=block.parentElement;
    if(parts.length&&block!==lastBlock){parts.push('\n');length++;}lastBlock=block;
    nodes.push({node:node,start:length});parts.push(node.nodeValue);length+=node.nodeValue.length;
    if(length>2000000)throw new Error('Speech text is too large');
   }
   var text=parts.join('');
   function clear(){
    activeRange=null;wordRange=null;
    if(window.CSS&&CSS.highlights){CSS.highlights.delete('ckreader-speech');CSS.highlights.delete('ckreader-speech-word');}
    if(overlay){overlay.remove();overlay=null;}
    if(wordOverlay){wordOverlay.remove();wordOverlay=null;}
   }
   function paint(){
    if(!activeRange)return;
    if(window.CSS&&CSS.highlights&&window.Highlight){
     if(!document.getElementById('ckreader-speech-style')){
      var style=document.createElementNS('http://www.w3.org/1999/xhtml','style');style.id='ckreader-speech-style';
      style.textContent='::highlight(ckreader-speech){background-color:rgba(255,190,30,.48);color:inherit;}::highlight(ckreader-speech-word){background-color:rgba(49,124,255,.7);color:white;}';document.head.appendChild(style);
     }
     CSS.highlights.set('ckreader-speech',new Highlight(activeRange));
     if(wordRange)CSS.highlights.set('ckreader-speech-word',new Highlight(wordRange));
    }else{
     if(overlay)overlay.remove();
     if(wordOverlay){wordOverlay.remove();wordOverlay=null;}
     overlay=document.createElementNS('http://www.w3.org/1999/xhtml','div');overlay.setAttribute('data-ckreader-speech','');overlay.setAttribute('aria-hidden','true');
     overlay.style.cssText='position:absolute!important;left:0!important;top:0!important;width:0!important;height:0!important;pointer-events:none!important;z-index:2147483646!important;';
     Array.from(activeRange.getClientRects()).forEach(function(r){if(r.width<=0||r.height<=0)return;
      var mark=document.createElementNS('http://www.w3.org/1999/xhtml','div');
      mark.style.cssText='position:absolute!important;pointer-events:none!important;background:rgba(255,190,30,.48)!important;left:'+(r.left+scrollX)+'px!important;top:'+(r.top+scrollY)+'px!important;width:'+r.width+'px!important;height:'+r.height+'px!important;';overlay.appendChild(mark);
     });document.documentElement.appendChild(overlay);
     if(wordRange){
      wordOverlay=document.createElementNS('http://www.w3.org/1999/xhtml','div');wordOverlay.setAttribute('data-ckreader-speech','');
      wordOverlay.style.cssText='position:absolute!important;left:0!important;top:0!important;pointer-events:none!important;z-index:2147483647!important;';
      Array.from(wordRange.getClientRects()).forEach(function(r){if(r.width<=0||r.height<=0)return;
       var mark=document.createElementNS('http://www.w3.org/1999/xhtml','div');
       mark.style.cssText='position:absolute!important;pointer-events:none!important;background:rgba(49,124,255,.7)!important;left:'+(r.left+scrollX)+'px!important;top:'+(r.top+scrollY)+'px!important;width:'+r.width+'px!important;height:'+r.height+'px!important;';wordOverlay.appendChild(mark);
      });document.documentElement.appendChild(wordOverlay);
     }
    }
   }
   function locate(start,end){
    var first=null,last=null;
    for(var i=0;i<nodes.length;i++){var n=nodes[i];
     if(!first&&n.start+n.node.length>start)first=n;
     if(n.start<end)last=n;else break;
    }
    if(!first||!last||end<=start)return null;
    var r=document.createRange();r.setStart(first.node,Math.max(0,start-first.start));r.setEnd(last.node,Math.min(last.node.length,end-last.start));
    var rects=Array.from(r.getClientRects()).filter(function(b){return b.width>0&&b.height>0;});
    if(!rects.length)return null;
    var v=window.visualViewport,width=v?v.width:innerWidth;
    return {spread:Math.max(0,Math.floor((rects[0].left+scrollX)/width+.001)),start:start,end:end};
   }
   function highlight(start,end){
    clear();var first=null,last=null;
    for(var i=0;i<nodes.length;i++){var n=nodes[i];
     if(!first&&n.start+n.node.length>start)first=n;
     if(n.start<end)last=n;else break;
    }
    if(!first||!last||end<=start)return null;
    var r=document.createRange();r.setStart(first.node,Math.max(0,start-first.start));r.setEnd(last.node,Math.min(last.node.length,end-last.start));
    activeRange=r;paint();var rects=Array.from(r.getClientRects()).filter(function(b){return b.width>0&&b.height>0;});
    if(!rects.length)return null;
    var v=window.visualViewport,width=v?v.width:innerWidth;
    return {spread:Math.max(0,Math.floor((rects[0].left+scrollX)/width+.001)),start:start,end:end};
   }
   function word(start,end){
    wordRange=null;if(wordOverlay){wordOverlay.remove();wordOverlay=null;}
    var first=null,last=null;
    for(var i=0;i<nodes.length;i++){var n=nodes[i];
     if(!first&&n.start+n.node.length>start)first=n;
     if(n.start<end)last=n;else break;
    }
    if(!first||!last||end<=start)return null;
    var r=document.createRange();r.setStart(first.node,Math.max(0,start-first.start));r.setEnd(last.node,Math.min(last.node.length,end-last.start));
    wordRange=r;paint();
    var rects=Array.from(r.getClientRects()).filter(function(b){return b.width>0&&b.height>0;});
    if(!rects.length)return null;
    var v=window.visualViewport,width=v?v.width:innerWidth;
    return {spread:Math.max(0,Math.floor((rects[0].left+scrollX)/width+.001))};
   }
   addEventListener('resize',paint);
   return {text:text,nodes:nodes,highlight:highlight,word:word,locate:locate,clear:clear,get range(){return activeRange;}};
  })();}
 """
 fun script(x:Float?=null,y:Float?=null):String {
  val point=if(x!=null&&y!=null)"[left+$x*width,top+$y*height]" else "null"
  return """(function(){try{
   var viewport=window.visualViewport,left=viewport?viewport.offsetLeft:0,top=viewport?viewport.offsetTop:0;
   var width=viewport?viewport.width:innerWidth,height=viewport?viewport.height:innerHeight;
   $documentScript
   var point=$point,nodes=window.__ckReaderSpeech.nodes,text=window.__ckReaderSpeech.text,first=-1;
   function visible(rect){return rect.width>0&&rect.height>0&&rect.right>left&&rect.left<left+width&&rect.bottom>top&&rect.top<top+height;}
   function intersects(n,a,b){var r=document.createRange();r.setStart(n,a);r.setEnd(n,b);return Array.from(r.getClientRects()).some(visible);}
   function firstVisible(n,a,b){if(a>=b||!intersects(n,a,b))return -1;if(b-a<=1)return a;var m=(a+b)>>1,l=firstVisible(n,a,m);return l>=0?l:firstVisible(n,m,b);}
   for(var i=0;i<nodes.length&&first<0;i++){
    var n=nodes[i],node=n.node;
    if(node.nodeValue.trim()){var at=firstVisible(node,0,node.nodeValue.length);if(at>=0)first=n.start+at;}
   }
   var offset=first;
   if(point){
    var r=document.caretRangeFromPoint?document.caretRangeFromPoint(point[0],point[1]):null;
    if(!r&&document.caretPositionFromPoint){var p=document.caretPositionFromPoint(point[0],point[1]);if(p){r=document.createRange();r.setStart(p.offsetNode,p.offset);}}
    if(!r)return null;var match=nodes.find(function(n){return n.node===r.startContainer;});if(!match)return null;
    offset=match.start+r.startOffset;
   }
   if(offset<0)return null;while(offset<text.length&&/\s/.test(text[offset]))offset++;
   return {text:text,offset:offset};
  }catch(e){return null;}})()"""
 }
}
