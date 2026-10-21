'use strict';
(function(){
  const root=document.querySelector('#settings,#settingsPage');
  if(!root)return;
  const panels=[...root.children].filter(el=>el.matches('.panel,section.panel'));
  for(const panel of panels){
    if(panel.parentElement?.tagName==='DETAILS')continue;
    const heading=panel.querySelector('h2');
    const title=(heading?.textContent||'설정').trim();
    const details=document.createElement('details');
    details.className='settingsFold';
    const summary=document.createElement('summary');
    summary.textContent=title;
    root.insertBefore(details,panel);
    details.append(summary,panel);
  }
})();