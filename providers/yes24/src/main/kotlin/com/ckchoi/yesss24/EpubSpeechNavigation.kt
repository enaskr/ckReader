package com.ckchoi.yesss24

/** Reconnect speech highlighting only when the user returns to the spoken spread. */
internal object EpubSpeechNavigation {
 fun shouldReconnect(playing:Boolean,currentChapter:Int,speechChapter:Int,
                     visibleSpread:Int,spokenSpread:Int):Boolean =
  playing && currentChapter==speechChapter && visibleSpread==spokenSpread &&
   visibleSpread>=0
}
