package com.ckchoi.ckreader

internal data class StorageIconChoice(val id:String,val label:String,val resource:Int=0,
 val region:IconRegion?=null)

/** User-supplied originals packaged as Android drawable icons, with no color tint. */
internal object StorageIcons {
 val builtIn=listOf(
  // Additional user-provided logos, kept as standalone selectable images.
  StorageIconChoice("user_blue_smilebox","블루 스마일 박스",R.drawable.storage_icon_user_blue_smilebox),
  StorageIconChoice("user_five","숫자 5",R.drawable.storage_icon_user_five),
  StorageIconChoice("user_infinicloud","InfiniCLOUD",R.drawable.storage_icon_user_infinicloud),
  StorageIconChoice("user_baidu","Baidu",R.drawable.storage_icon_user_baidu),
  StorageIconChoice("user_terabox","TeraBox",R.drawable.storage_icon_user_terabox),
  // User-provided storage images, preserved as an additional selectable set.
  StorageIconChoice("user_blue_folder","블루 폴더 (추가 아이콘)",R.drawable.storage_icon_user_blue_folder),
  StorageIconChoice("user_webdav_cloud","WebDAV 클라우드 (추가 아이콘)",R.drawable.storage_icon_user_webdav_cloud),
  StorageIconChoice("user_bookoasis","북오아시스 야자수 (추가 아이콘)",R.drawable.storage_icon_user_bookoasis),
  StorageIconChoice("user_orange_folder","오렌지 폴더 (추가 아이콘)",R.drawable.storage_icon_user_orange_folder),
  StorageIconChoice("google_cloud","Google Cloud",R.drawable.storage_icon_google_cloud),
  StorageIconChoice("cloud_blue","블루 클라우드",R.drawable.storage_icon_cloud_blue),
  StorageIconChoice("webdav","WebDAV",R.drawable.storage_icon_webdav),
  StorageIconChoice("onedrive","OneDrive",R.drawable.storage_icon_onedrive),
  StorageIconChoice("ftp_badge","FTP 배지",R.drawable.storage_icon_ftp_badge),
  StorageIconChoice("ftp_network","FTP 네트워크",R.drawable.storage_icon_ftp_network),
  StorageIconChoice("ftp_folder","FTP 폴더",R.drawable.storage_icon_ftp_folder),
  StorageIconChoice("sftp","SFTP",R.drawable.storage_icon_sftp),
  StorageIconChoice("sftp_secure","SFTP 보안",R.drawable.storage_icon_sftp_secure),
  StorageIconChoice("rclone","Rclone",R.drawable.storage_icon_rclone,IconRegion(40,70,205,245)),
  StorageIconChoice("google_drive","Google Drive",R.drawable.storage_icon_google_drive),
  StorageIconChoice("box","Box",R.drawable.storage_icon_box),
  StorageIconChoice("dropbox","Dropbox",R.drawable.storage_icon_dropbox),
  StorageIconChoice("cloud_upload","클라우드 업로드",R.drawable.storage_icon_cloud_upload),
  StorageIconChoice("cloud_red","레드 클라우드",R.drawable.storage_icon_cloud_red),
  StorageIconChoice("cloud_sync","클라우드 동기화",R.drawable.storage_icon_cloud_sync),
  StorageIconChoice("s3","S3",R.drawable.storage_icon_s3),
  StorageIconChoice("s3_bucket","S3 버킷",R.drawable.storage_icon_s3_bucket),
  StorageIconChoice("cloud_network","클라우드 네트워크",R.drawable.storage_icon_cloud_network),
  StorageIconChoice("cloud_code","클라우드 코드",R.drawable.storage_icon_cloud_code),
  StorageIconChoice("naver_cloud","NAVER Cloud",R.drawable.storage_icon_naver_cloud),
  StorageIconChoice("cloud_chat","클라우드 채팅",R.drawable.storage_icon_cloud_chat),
  StorageIconChoice("cloud_yellow","옐로 클라우드",R.drawable.storage_icon_cloud_yellow),
  StorageIconChoice("cloud_cyan","시안 클라우드",R.drawable.storage_icon_cloud_cyan),
  StorageIconChoice("samsung_cloud","Samsung Cloud",R.drawable.storage_icon_samsung_cloud),
  StorageIconChoice("cloud_sync_square","동기화 배지",R.drawable.storage_icon_cloud_sync_square),
  StorageIconChoice("cloud_transfer","클라우드 전송",R.drawable.storage_icon_cloud_transfer),
  StorageIconChoice("ibm_cloud","IBM Cloud",R.drawable.storage_icon_ibm_cloud),
  StorageIconChoice("red_hat","Red Hat",R.drawable.storage_icon_red_hat),
  StorageIconChoice("akamai","Akamai",R.drawable.storage_icon_akamai),
  StorageIconChoice("cloud_arrows","클라우드 화살표",R.drawable.storage_icon_cloud_arrows),
  StorageIconChoice("cloudinary","Cloudinary",R.drawable.storage_icon_cloudinary),
  StorageIconChoice("google_photos","Google Photos",R.drawable.storage_icon_google_photos),
  StorageIconChoice("google_photos_classic","Google Photos 클래식",R.drawable.storage_icon_google_photos_classic),
  StorageIconChoice("cloud_purple","퍼플 업로드",R.drawable.storage_icon_cloud_purple),
  StorageIconChoice("smb","SMB",R.drawable.storage_icon_smb),
  StorageIconChoice("pcloud","pCloud",R.drawable.storage_icon_pcloud),
  StorageIconChoice("storage_stripes","블루 스트라이프",R.drawable.storage_icon_storage_stripes),
  StorageIconChoice("oracle","Oracle",R.drawable.storage_icon_oracle),
  StorageIconChoice("storage_red","레드 스토리지",R.drawable.storage_icon_storage_red),
  StorageIconChoice("azure_classic","Azure 클래식",R.drawable.storage_icon_azure_classic),
  StorageIconChoice("azure","Azure",R.drawable.storage_icon_azure),
  StorageIconChoice("mail_cloud","메일 클라우드",R.drawable.storage_icon_mail_cloud),
  StorageIconChoice("disk_blue","블루 디스크",R.drawable.storage_icon_disk_blue),
  StorageIconChoice("disk_cyan","시안 디스크",R.drawable.storage_icon_disk_cyan),
  StorageIconChoice("shared_folder","공유 폴더",R.drawable.storage_icon_shared_folder),
  StorageIconChoice("shared_folder_outline","공유 폴더 라인",R.drawable.storage_icon_shared_folder_outline),
  StorageIconChoice("gofile","GoFile",R.drawable.storage_icon_gofile),
  StorageIconChoice("cloudflare","Cloudflare",R.drawable.storage_icon_cloudflare),
  StorageIconChoice("storage_color","컬러 스토리지",R.drawable.storage_icon_storage_color),
  StorageIconChoice("naver_mybox","네이버 MYBOX",R.drawable.storage_icon_naver_mybox),
  StorageIconChoice("naver_drive","네이버 드라이브",R.drawable.storage_icon_naver_drive),
  StorageIconChoice("nas","NAS",R.drawable.storage_icon_nas),
  StorageIconChoice("cloud_upload_sky","스카이 업로드",R.drawable.storage_icon_cloud_upload_sky),
  StorageIconChoice("zip","ZIP 보관함",R.drawable.storage_icon_zip)
 )
 fun defaultFor(type:String):String?=when(type) {
  "drive"->"google_drive"
  "webdav"->"webdav"
  "ftp"->"ftp_folder"
  else->null
 }
}
