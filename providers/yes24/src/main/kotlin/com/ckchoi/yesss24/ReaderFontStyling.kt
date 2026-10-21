package com.ckchoi.yesss24

/**
 * User font choice must win over EPUB publisher CSS, including typeface
 * declarations on nested paragraphs and spans. Original mode (0) preserves
 * the book's own typefaces without an overriding family declaration.
 */
internal object ReaderFontStyling {
 fun css(choice:Int,installedId:String?=null,otf:Boolean=false):String {
  if(choice==0)return ""
  val override=when {
   installedId!=null && choice>=3->"'CKReaderUserFont', serif"
   choice==1->"serif"
   choice==2->"sans-serif"
   else->return ""
  }
  val source=if(installedId!=null&&choice>=3) {
   // The id originates from ReaderFonts.families and not from an EPUB.
   require(installedId.matches(Regex("[a-z0-9-]{1,50}")))
   val format=if(otf)"opentype" else "truetype"
   """@font-face { font-family:'CKReaderUserFont';
    src:url('https://epub.local/__ckreader_fonts__/$installedId') format('$format');
    font-style:normal; font-weight:normal; font-display:swap; }"""
  } else ""
  // Override author CSS rather than merely relying on body inheritance.
  // SVG graphics retain their own typography and image rendering.
  return source+"\nhtml body, html body *:not(svg):not(svg *) {"+
   "font-family:$override !important;}"
 }
}
