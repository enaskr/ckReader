package com.ckchoi.ckreader

internal object LibraryRefreshPolicy {
 const val MESSAGE="진행중인 캐시 생성 작업을 중단하고, 현재 폴더의 캐시생성 작업을 진행하시겠습니까?"
 fun depth(type:String,folder:String):String=if(type=="local"||folder.isEmpty())"__root__" else folder
}
