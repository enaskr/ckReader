'use strict';
/* Local, self-contained reading-life notebook. No remote account or DRM hooks. */
(()=>{
 const $=id=>document.getElementById(id);
 const STORAGE_KEY='crema-standalone-reading-life-v1';
 const GOAL_KEY='crema-standalone-reading-goal-v1';
 const now=new Date();
 let month=new Date(now.getFullYear(),now.getMonth(),1);
 let selectedDay='';
 const pad=n=>String(n).padStart(2,'0');
 const formatDate=d=>d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate());
 function readLogs(){
  try {
   const a=JSON.parse(localStorage.getItem(STORAGE_KEY)||'[]');
   if(!Array.isArray(a))return [];
   return a.filter(x=>x && typeof x.title==='string' && /^\d{4}-\d{2}-\d{2}$/.test(x.date||'')).slice(-2000);
  } catch(_){return [];}
 }
 function writeLogs(items){
  try {localStorage.setItem(STORAGE_KEY,JSON.stringify(items));return true;}
  catch(_) { if(typeof toast==='function')toast('저장 공간을 사용할 수 없습니다.');return false;}
 }
 function readGoal(){
  let n=12;
  try{n=Number(localStorage.getItem(GOAL_KEY)||12);}catch(_){}
  return Number.isInteger(n)&&n>=1&&n<=1000?n:12;
 }
 function saveGoal(n){
  if(!Number.isInteger(n)||n<1||n>1000){if(typeof toast==='function')toast('독서 목표는 1~1000 사이에서 입력해주세요.');return;}
  try{localStorage.setItem(GOAL_KEY,String(n));}catch(_){return;}
  render();
 }
 const append=(p,tag,cls,text)=>{
  const el=document.createElement(tag);if(cls)el.className=cls;
  if(text!==undefined)el.textContent=text;p.appendChild(el);return el;
 };
 function monthlyRows(rows){const prefix=formatDate(month).slice(0,7);return rows.filter(r=>r.date.startsWith(prefix));}
 function renderCalendar(rows){
  const calendar=$('lifeCalendar');calendar.replaceChildren();
  for(const name of ['일','월','화','수','목','금','토'])append(calendar,'span','weekDay',name);
  const y=month.getFullYear(),m=month.getMonth();
  const initial=new Date(y,m,1).getDay(),days=new Date(y,m+1,0).getDate();
  for(let i=0;i<initial;i++)append(calendar,'span','blankDay','');
  const today=formatDate(new Date());
  const recordDays=new Set(rows.map(r=>r.date));
  for(let day=1;day<=days;day++){
   const date=y+'-'+pad(m+1)+'-'+pad(day);
   const b=append(calendar,'button','',String(day));
   b.type='button';b.setAttribute('aria-label',date+' 독서 기록');
   if(recordDays.has(date))b.classList.add('hasReading');
   if(date===today)b.classList.add('currentDay');
   if(selectedDay===date)b.classList.add('chosenDay');
   b.onclick=()=>{selectedDay=selectedDay===date?'':date;render();};
  }
 }
 function renderHistory(rows){
  const root=$('lifeHistory');root.replaceChildren();
  const filtered=selectedDay?rows.filter(r=>r.date===selectedDay):rows;
  $('lifeEmpty').hidden=filtered.length>0;
  for(const item of [...filtered].sort((a,b)=>b.date.localeCompare(a.date)||String(b.id).localeCompare(String(a.id)))){
   const row=append(root,'article','lifeHistoryRow');
   const top=append(row,'div','lifeRowTop');
   const name=append(top,'h3','',item.title);
   const remove=append(top,'button','lifeDelete','삭제');remove.type='button';
   remove.setAttribute('aria-label',item.title+' 기록 삭제');
   remove.onclick=()=>{
    if(!confirm('선택한 독서 기록을 삭제하시겠습니까?'))return;
    if(writeLogs(readLogs().filter(r=>r.id!==item.id)))render();
   };
   append(row,'time','',item.date);
   append(row,'p','lifeHint',(Number(item.pages)||0)+'페이지 · '+(Number(item.minutes)||0)+'분');
   if(item.memo)append(row,'p','',item.memo);
  }
 }
 function render(){
  const all=readLogs(),monthly=monthlyRows(all),goal=readGoal();
  const books=new Set(monthly.map(x=>x.title.trim().toLocaleLowerCase('ko-KR'))).size;
  const pages=monthly.reduce((n,r)=>n+Math.max(0,Number(r.pages)||0),0);
  const minutes=monthly.reduce((n,r)=>n+Math.max(0,Number(r.minutes)||0),0);
  $('lifeReadCount').textContent=String(books);
  $('lifePages').textContent=String(pages);
  $('lifeMinutes').textContent=String(minutes);
  $('readingGoal').value=String(goal);
  $('lifeGoalProgress').max=goal;
  $('lifeGoalProgress').value=Math.min(books,goal);
  $('lifeGoalText').textContent='이번 달 '+books+'권 / 목표 '+goal+'권';
  $('lifeMonthLabel').textContent=month.getFullYear()+'년 '+(month.getMonth()+1)+'월';
  renderCalendar(monthly);
  renderHistory(all);
 }
 $('lifeReadDate').value=formatDate(new Date());
 $('lifeReadDate').max=formatDate(new Date());
 $('readingGoal').value=String(readGoal());
 $('readingGoal').onchange=e=>saveGoal(Number(e.target.value));
 $('lifePrevMonth').onclick=()=>{month=new Date(month.getFullYear(),month.getMonth()-1,1);selectedDay='';render();};
 $('lifeNextMonth').onclick=()=>{month=new Date(month.getFullYear(),month.getMonth()+1,1);selectedDay='';render();};
 $('lifeAllDates').onclick=()=>{selectedDay='';render();};
 $('readLogForm').onsubmit=e=>{
  e.preventDefault();
  const title=$('lifeBookTitle').value.trim(),date=$('lifeReadDate').value;
  const pages=Number($('lifeReadPages').value),minutes=Number($('lifeReadMinutes').value);
  if(!title||title.length>120||!Date.parse(date)||date>formatDate(new Date())||date<'2000-01-01'){
   if(typeof toast==='function')toast('책 제목과 읽은 날짜를 확인해주세요.');return;
  }
  if(!Number.isInteger(pages)||!Number.isInteger(minutes)||pages<0||pages>100000||minutes<0||minutes>1440){
   if(typeof toast==='function')toast('페이지 또는 독서 시간을 확인해주세요.');return;
  }
  if(!pages&&!minutes){
   if(typeof toast==='function')toast('페이지나 독서 시간을 입력해주세요.');return;
  }
  const rows=readLogs();
  rows.push({id:Date.now()+'-'+Math.random().toString(36).slice(2,9),title,date,pages,minutes,memo:$('lifeReadMemo').value.trim().slice(0,3000)});
  if(writeLogs(rows)){
   month=new Date(Number(date.slice(0,4)),Number(date.slice(5,7))-1,1);
   selectedDay='';
   $('readLogForm').reset();
   $('lifeReadDate').value=formatDate(new Date());
   if(typeof toast==='function')toast('독서 기록을 저장했습니다.');
   render();
  }
 };
 $('openElectronicBrowse').onclick=()=>tab('electronicBrowse');
 for(const selector of ['.openPdf','.openEpub']){
  document.querySelectorAll(selector).forEach(button=>{
   button.addEventListener('click',()=>{
    const format=selector==='.openPdf'?'pdf':'epub';
    if(window.Crema?.openLocalBook)window.Crema.openLocalBook(format);
    else if(typeof toast==='function')toast('Android 앱에서만 파일을 열 수 있습니다.');
   });
  });
 }
 window.setCremaTheme=dark=>{
  $('cremaDarkMode').checked=!!dark;
  document.documentElement.dataset.theme=dark?'black':'white';
 };
 $('cremaDarkMode').onchange=e=>{
  if(window.Crema?.setDarkMode)window.Crema.setDarkMode(!!e.target.checked);
  else window.setCremaTheme(!!e.target.checked);
 };
 window.renderReadingLife=render;
 render();
})();