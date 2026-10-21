package com.ckchoi.yesss24;

import org.json.*;

/** Account snapshots live only inside the encrypted SecureStore payload. */
public final class CremaAccounts {
 private final JSONObject profiles;
 private String active;
 public CremaAccounts(JSONObject saved) throws JSONException {
  profiles = saved.optJSONObject("accounts") == null ? new JSONObject() : copy(saved.getJSONObject("accounts"));
  active = saved.optString("activeAccount", "");
  if (!saved.has("accounts")) {
   JSONObject legacy = saved.optJSONObject("session");
   if (legacy != null && !legacy.optString("userId").isEmpty())
    capture(legacy, saved.optJSONArray("cookies") == null ? new JSONArray() : saved.getJSONArray("cookies"));
  }
  if (!profiles.has(active)) active = "";
 }
 private static JSONObject copy(JSONObject value) throws JSONException { return new JSONObject(value.toString()); }
 public String activeId() { return active; }
 public JSONObject snapshot() throws JSONException {
  return profiles.has(active) ? copy(profiles.getJSONObject(active)) : new JSONObject();
 }
 public void capture(JSONObject session, JSONArray cookies) throws JSONException {
  String id = session.optString("userId");
  if (id.isEmpty()) return;
  profiles.put(id, new JSONObject().put("session", copy(session)).put("cookies", new JSONArray(cookies.toString())));
  active = id;
 }
 public void select(String id) throws JSONException {
  if (!id.isEmpty() && !profiles.has(id)) throw new JSONException("저장된 Crema 계정을 선택해주세요.");
  active = id;
 }
 public void logout() throws JSONException {
  if (profiles.has(active)) {
   JSONObject old = profiles.getJSONObject(active).getJSONObject("session");
   capture(new JSONObject().put("userId", active).put("userNo", old.optString("userNo")), new JSONArray());
  }
 }
 public void remove(String id) throws JSONException {
  if (!profiles.has(id)) throw new JSONException("저장된 Crema 계정을 선택해주세요.");
  profiles.remove(id);
  if (active.equals(id)) active = profiles.length() == 0 ? "" : profiles.keys().next();
 }
 public JSONArray summaries() throws JSONException {
  JSONArray result = new JSONArray();
  for (java.util.Iterator<String> keys = profiles.keys(); keys.hasNext();) {
   String id = keys.next();
   JSONObject session = profiles.getJSONObject(id).getJSONObject("session");
   result.put(new JSONObject().put("userId", id).put("loggedIn", !session.optString("token").isEmpty()).put("active", active.equals(id)));
  }
  return result;
 }
 public JSONObject serialize(JSONObject known) throws JSONException {
  return new JSONObject().put("accounts", copy(profiles)).put("activeAccount", active).put("known", known);
 }
}
