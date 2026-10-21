package com.ckchoi.yesss24

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Store(val context: Context) {
 val prefs = context.getSharedPreferences("yesss24", Context.MODE_PRIVATE)
 private fun key(): SecretKey {
  val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  return (ks.getKey("yesss24.account", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
   init(KeyGenParameterSpec.Builder("yesss24.account",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  }.generateKey()
 }
 fun account(slot: String = "account"): JSONObject {
  val data = prefs.getString(slot, null) ?: return JSONObject()
  val bytes = Base64.decode(data, Base64.NO_WRAP)
  val cipher = Cipher.getInstance("AES/GCM/NoPadding")
  cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
  return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
 }
 fun save(account: JSONObject, slot: String = "account") {
  check(prefs.edit().putString(slot,encode(account)).commit())
 }
 fun encode(account: JSONObject): String {
  val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key())
  val bytes = cipher.iv + cipher.doFinal(account.toString().toByteArray(Charsets.UTF_8))
  return Base64.encodeToString(bytes,Base64.NO_WRAP)
 }
}
