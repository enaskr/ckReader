package com.ckchoi.ckreader

import java.util.Locale

/** One-letter provenance of the highest-priority *verified* data source. */
internal object MetadataSourceBadge {
 fun label(code:String):String=when(code) {
  "K"->"kavita.yaml"
  "Y"->"도서 파일 YAML"
  "A"->"알라딘 OpenAPI"
  "N"->"국립중앙도서관 OpenAPI"
  "L"->"도서관 정보나루 OpenAPI"
  "B"->"Book Oasis API"
  "W"->"기타 도서정보"
  else->"도서정보 없음"
 }
 fun code(source:String):String {
  val value=source.trim().lowercase(Locale.ROOT)
  if(value.isBlank())return "?"
  return when {
   Regex("""(?:^|[/\\ ·])kavita\.ya?ml(?:$|[ ·])""").containsMatchIn(value) ||
    Regex("""(?:^|[/\\ ·])kavita\.yml(?:$|[ ·])""").containsMatchIn(value)->"K"
   (value.contains(".yaml") || value.contains(".yml")) &&
    (value.startsWith("yaml")||value.contains(" · ") || value.endsWith(".yaml")||value.endsWith(".yml")) -> "Y"
   value.startsWith("알라딘")||value.startsWith("aladin")->"A"
   value.startsWith("국립중앙도서관")||value.startsWith("national library")->"N"
   value.startsWith("도서관 정보나루")||value.startsWith("도서관정보나루")||
    value.startsWith("도서관 정보마루")||value.startsWith("도서관정보마루")||
    value.startsWith("data4library")->"L"
   value=="book oasis api"->"B"
   else->"W"
  }
 }
 fun isKavita(source:String):Boolean=code(source)=="K"
 fun isYaml(source:String):Boolean=code(source) in setOf("K","Y")
 fun isOfficial(source:String):Boolean=code(source) in setOf("A","N","L")
}
