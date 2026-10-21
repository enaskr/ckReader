'use strict';
let yes24Loaded=false,yes24Serial=0;
const normalized=s=>(s||'').replace(/\s+/g,'').toLowerCase();
function yes24Image(value){if(!value)return'';if(value.startsWith('//'))return'https:'+value;if(value.startsWith('/'))return'https://www.yes24.com'+value;return value;}
function yes24Cover(value,id){const image=yes24Image(value||'');return !image||/gImgLoad\.gif|loading\.gif|data:image/i.test(image)?'https://image.yes24.com/goods/'+id+'/L':image;}
function yes24Books(scope,limit=24){
 const result=[],seen=new Set();
 for(const link of scope.querySelectorAll('a[href*="/Product/Goods/"]')){
  const id=link.getAttribute('href')?.match(/\/Product\/Goods\/(\d+)/i)?.[1];if(!id||seen.has(id))continue;
  const box=link.closest('li,.itemUnit,.goods_info,.goodsList_item,.cCont_goodsSet')||link.parentElement;
  const imageEl=box?.querySelector('img')||link.querySelector('img');
  const title=(link.getAttribute('title')||link.textContent||imageEl?.alt||'').trim().replace(/\s+/g,' ');
  if(!title||title==='상품상세로 이동'||title.includes('상세페이지 이동'))continue;
  const image=yes24Cover(imageEl?.getAttribute('data-original')||imageEl?.getAttribute('data-src')||imageEl?.getAttribute('src'),id);
  const author=(box?.querySelector('.info_auth,.authPub,.goods_auth,.txt_auth')?.textContent||'').trim().replace(/\s+/g,' ');
  seen.add(id);result.push({id,title,image,author});if(result.length>=limit)break;
 }
 return result;
}
function yes24Section(page,title){
 const heading=[...page.querySelectorAll('h1,h2,h3,h4,strong')].find(e=>normalized(e.textContent)===normalized(title));
 if(!heading)return[];
 let node=heading.parentElement,best=null;
 while(node&&node!==page.body){if(node.querySelectorAll('a[href*="/Product/Goods/"]').length>=3){best=node;break;}node=node.parentElement;}
 return yes24Books(best||heading.parentElement);
}
function renderYes24(el,books,rank=false){
 el.replaceChildren();if(!books.length){fail(el,'표시할 eBook이 없습니다.');return;}
 for(const [index,b] of books.entries()){const card=document.createElement('button');card.type='button';card.className='book yes24Book';card.setAttribute('aria-label',b.title+' 상세 보기');card.innerHTML='<span class="cover"><img loading="lazy" src="'+escapeHTML(b.image)+'" alt="'+escapeHTML(b.title)+' 표지"></span>'+(rank?'<span class="rank">'+(index+1)+'</span>':'')+'<span class="name">'+escapeHTML(b.title)+'</span><span class="author">'+escapeHTML(b.author)+'</span>';card.querySelector('img').onerror=e=>{e.target.src='icon.png';e.target.style.opacity='.2';};card.onclick=()=>openYes24Detail(b);el.append(card);}
}
async function openYes24Detail(b){
 const serial=++detailSerial;loading($('#detailBody'));if(!$('#detail').open)$('#detail').showModal();
 try{const raw=await rpc('yes24EbookDetail',{id:b.id});if(serial!==detailSerial)return;const page=doc(raw);
  const title=text(page,'#goodsNm')||page.querySelector('meta[property="og:title"]')?.content?.replace(/\s*-\s*YES24.*$/i,'')||b.title;
  const author=text(page,'.gd_auth')||text(page,'.gd_infoTop .gd_auth')||b.author;
  const publisher=text(page,'.gd_pub');const image=yes24Cover(page.querySelector('meta[property="og:image"]')?.content,b.id);
  const introduction=prose(page.querySelector('#infoset_introduce'));const review=prose(page.querySelector('#infoset_pubReivew'));
  $('#detailBody').innerHTML='<div class="detailhero"><img src="'+escapeHTML(image)+'" alt="'+escapeHTML(title)+' 표지"><div><span class="eyebrow">YES24 EBOOK</span><h2>'+escapeHTML(title)+'</h2><p>'+escapeHTML(author)+'</p></div></div><dl class="metadata">'+[['저자',author],['출판사',publisher]].map(([k,v])=>'<dt>'+k+'</dt><dd>'+escapeHTML(v||'제공되지 않은 정보')+'</dd>').join('')+'</dl><div class="detailtabs"><button id="introTab" class="selected">책소개</button><button id="reviewTab">출판사 리뷰</button></div><div id="bookProse" class="prose"></div><p class="version">YES24 eBook 도서정보</p>';
  $('#bookProse').textContent=introduction;$('#introTab').onclick=()=>{$('#bookProse').textContent=introduction;$('#introTab').classList.add('selected');$('#reviewTab').classList.remove('selected');};$('#reviewTab').onclick=()=>{$('#bookProse').textContent=review;$('#reviewTab').classList.add('selected');$('#introTab').classList.remove('selected');};
 }catch(e){if(serial===detailSerial)fail($('#detailBody'),e,()=>openYes24Detail(b));}
}
async function loadYes24(){
 const serial=++yes24Serial;for(const id of ['yes24Today','yes24New','yes24Hot','yes24Best'])loading($('#'+id));
 try{const page=doc(await rpc('yes24Ebook'));if(serial!==yes24Serial)return;renderYes24($('#yes24Today'),yes24Section(page,'오늘의 eBook'));renderYes24($('#yes24New'),yes24Section(page,'새로 나온 eBook'));renderYes24($('#yes24Hot'),yes24Section(page,'분야별 HOT'));renderYes24($('#yes24Best'),yes24Section(page,'베스트셀러'),true);yes24Loaded=true;}
 catch(e){if(serial===yes24Serial)for(const id of ['yes24Today','yes24New','yes24Hot','yes24Best'])fail($('#'+id),e,loadYes24);}
}
async function yes24Search(term){
 const serial=++yes24Serial;$('#yes24Main').hidden=true;$('#yes24SearchArea').hidden=false;loading($('#yes24SearchBooks'));$('#yes24SearchInfo').textContent='“'+term+'” 검색 중…';
 try{const page=doc(await rpc('yes24Ebook',{query:term}));if(serial!==yes24Serial)return;const books=yes24Books(page,60);renderYes24($('#yes24SearchBooks'),books);$('#yes24SearchInfo').textContent='“'+term+'” 검색 결과 · '+books.length+'권';}
 catch(e){if(serial===yes24Serial)fail($('#yes24SearchBooks'),e,()=>yes24Search(term));}
}
window.yes24Tab=()=>{if(!yes24Loaded)loadYes24();};
$('#yes24Search').onsubmit=e=>{e.preventDefault();const term=$('#yes24Query').value.trim();if(!term){toast('eBook 검색어를 입력해주세요.');return;}yes24Search(term);};
$('#yes24SearchClear').onclick=()=>{$('#yes24SearchArea').hidden=true;$('#yes24Main').hidden=false;$('#yes24Query').value='';if(!yes24Loaded)loadYes24();};
