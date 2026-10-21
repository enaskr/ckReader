'use strict';
let settingsTransferActive=false;
function finishSettingsTransfer(message){
 settingsTransferActive=false;
 $('#backupSettings').disabled=false;$('#restoreSettings').disabled=false;
 if($('#settingsTransferProgress').open)$('#settingsTransferProgress').close();
 $('#settingsBackupMessage').textContent=message;
}
window.settingsTransferResult=result=>{
 if(result.error){finishSettingsTransfer(result.error);return;}
 if(result.cancelled){finishSettingsTransfer('취소했습니다. 설정은 변경하지 않았습니다.');return;}
 if(result.restored){
  invalidateCremaView();addingCremaAccount=false;cremaAccountChanging=false;$('#password').value='';
  session=result.status;renderStatus();applyLibrarySettings(result.libraries);
  $('#filenamePatternMessage').textContent='';$('#libSettingsMessage').textContent='';$('#loginMessage').textContent='';
  finishSettingsTransfer('계정정보와 앱 설정을 복원했습니다. 로그인 세션이 만료된 계정은 다시 로그인해주세요.');
  tab('settings');return;
 }
 if(result.path)finishSettingsTransfer('백업 완료 · '+result.path);
};
async function beginSettingsTransfer(action){
 if(settingsTransferActive||downloadBusy||cremaAccountChanging||yes24AccountChanging||adding.size||removing.size||downloading.size){toast('진행 중인 작업이 끝난 뒤 백업 / 복원해주세요.');return;}
 settingsTransferActive=true;$('#backupSettings').disabled=true;$('#restoreSettings').disabled=true;
 $('#settingsTransferProgressText').textContent=action==='backupSettings'?'계정정보와 앱 설정을 JSON으로 저장하고 있습니다.':'파일 선택기에서 복원할 JSON 파일을 선택해주세요.';
 $('#settingsTransferProgress').showModal();
 try{
  const result=await rpc(action,action==='backupSettings'?{selectedLibrary}:{});
  if(result.path||result.error||result.restored||result.cancelled)window.settingsTransferResult(result);
  else if(result.permissionPending&&settingsTransferActive)$('#settingsTransferProgressText').textContent='저장소 권한을 허용하면 백업을 이어서 저장합니다.';
 }catch(e){finishSettingsTransfer(e.message);}
}
$('#settingsTransferProgress').addEventListener('cancel',e=>e.preventDefault());
$('#backupSettings').onclick=()=>beginSettingsTransfer('backupSettings');
$('#restoreSettings').onclick=()=>beginSettingsTransfer('restoreSettings');
