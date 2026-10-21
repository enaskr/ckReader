package com.ckchoi.yesss24

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Shows which separately installed AI engine APKs are available. */
class AiVoiceDownloadActivity:Activity() {
 private lateinit var status:TextView
 override fun onCreate(state:Bundle?) {
  super.onCreate(state)
  title="AI 음성 모듈"
  val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;setPadding(36,36,36,36)}
  status=TextView(this).apply{textSize=17f}
  layout.addView(status)
  setContentView(layout)
 }
 override fun onResume(){super.onResume();status.text=buildString {
  append("Selvy I AI 음성: ");append(if(VoiceModules.installed(this@AiVoiceDownloadActivity,"yes24"))"설치됨" else "설치 필요")
  append("\nSelvy II AI 음성: ");append(if(VoiceModules.installed(this@AiVoiceDownloadActivity,"ridi"))"설치됨" else "설치 필요")
  append("\nONNX I AI 음성: ");append(if(VoiceModules.installed(this@AiVoiceDownloadActivity,"willa"))"설치됨" else "설치 필요")
  append("\n\n각 엔진 APK를 별도로 설치하면 듣기 화면의 음성 엔진 목록에 자동으로 표시됩니다.")
 }}
}
