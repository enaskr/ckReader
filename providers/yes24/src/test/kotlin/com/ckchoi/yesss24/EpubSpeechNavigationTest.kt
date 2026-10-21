package com.ckchoi.yesss24

import org.junit.Assert.*
import org.junit.Test

class EpubSpeechNavigationTest {
 @Test fun onlyReconnectsOnActiveUtteranceSpread() {
  assertTrue(EpubSpeechNavigation.shouldReconnect(true,4,4,9,9))
  assertFalse(EpubSpeechNavigation.shouldReconnect(true,4,4,8,9))
  assertFalse(EpubSpeechNavigation.shouldReconnect(true,3,4,9,9))
  assertFalse(EpubSpeechNavigation.shouldReconnect(false,4,4,9,9))
  assertFalse(EpubSpeechNavigation.shouldReconnect(true,4,4,-1,-1))
 }
 @Test fun positionLookupMustNotRepaintOrScroll() {
  val script=EpubSpeechHighlight.locate(11,18,"https://epub.local/chapter.xhtml")
  assertTrue(script.contains("window.__ckReaderSpeech.locate(11,18)"))
  assertFalse(script.contains("window.__ckReaderSpeech.highlight(11,18)"))
 }
}
