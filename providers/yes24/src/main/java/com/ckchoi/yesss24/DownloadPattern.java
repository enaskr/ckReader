package com.ckchoi.yesss24;

import java.io.IOException;
import java.util.regex.Pattern;

public final class DownloadPattern {
 public static final String DEFAULT = "{title}-{author}-{publisher}-{ebook_id}";
 private static final Pattern FIELDS = Pattern.compile("\\{(title|author|publisher|ebook_id)\\}");
 public static String validate(String input) throws IOException {
  String value = input.trim();
  String literal = FIELDS.matcher(value).replaceAll("");
  if(value.isEmpty() || value.length() > 180 || !FIELDS.matcher(value).find()
     || literal.matches("(?s).*[{}\\\\/:*?\"<>|\\p{Cntrl}].*"))
   throw new IOException("파일명 규칙에는 {title}, {author}, {publisher}, {ebook_id}와 일반 문자를 사용해주세요.");
  return value;
 }
}
