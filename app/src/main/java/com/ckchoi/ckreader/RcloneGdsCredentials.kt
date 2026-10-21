package com.ckchoi.ckreader

import org.json.JSONObject

/** GDS display/read-only mode may use either custom GDS credentials or standard Drive OAuth. */
internal object RcloneGdsCredentials {
 fun custom(config:JSONObject):Boolean =
  config.optString("gds_userid").isNotBlank() &&
  config.optString("gds_apikey").isNotBlank() &&
  config.optString("gds_endpoint").isNotBlank()

 fun oauth(config:JSONObject):Boolean =
  config.optString("token").isNotBlank() ||
  config.optString("service_account_file").isNotBlank()

 fun usable(config:JSONObject):Boolean =custom(config)||oauth(config)
}
