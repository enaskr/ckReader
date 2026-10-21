package com.ckchoi.ckreader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts user-entered metadata API keys in separate provider vaults.
 * No key is included in the APK, source files, logs or public SQLite cache.
 * The alias is intentionally separate from storage OAuth/credential aliases.
 */
internal enum class MetadataApiProvider(val label:String,val preferences:String,val alias:String) {
 ALADIN("알라딘", "ckreader_aladin_api", "ckreader_aladinapi_v1"),
 NATIONAL_LIBRARY("국립중앙도서관", "ckreader_national_library_api", "ckreader_national_library_api_v1"),
 DATA4LIBRARY("도서관 정보나루", "ckreader_data4library_api", "ckreader_data4library_api_v1")
}

internal class AladinApiCredentials(context:Context,
 private val provider:MetadataApiProvider=MetadataApiProvider.ALADIN) {
 private val prefs=context.getSharedPreferences(provider.preferences,Context.MODE_PRIVATE)
 private val lock=companionLock

 companion object {
  private const val KEY_NAME="ciphertext"
  private val companionLock=Any()
  // Do not persist, log, or return the plaintext through UI binding.
  fun validKey(raw:String):Boolean =
   raw.isNotBlank()&&raw.length in 8..512&&
    raw.none{it=='\r'||it=='\n'||it.isISOControl()}
 }

 private fun secret():SecretKey {
  val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  (store.getKey(provider.alias,null) as? SecretKey)?.let{return it}
  return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
   init(KeyGenParameterSpec.Builder(this@AladinApiCredentials.provider.alias,
    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
    .setKeySize(256)
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
    .setRandomizedEncryptionRequired(true).build())
  }.generateKey()
 }
 fun configured():Boolean=prefs.contains(KEY_NAME)
 fun revision():Int=prefs.getInt("revision",0)
 fun save(value:String):Boolean {
  if(!validKey(value))return false
  return synchronized(lock){
   runCatching {
    val cipher=Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE,secret())
    val encrypted=cipher.iv+cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    val encoded=Base64.encodeToString(encrypted,Base64.NO_WRAP)
    val next=revision()+1
    prefs.edit().putString(KEY_NAME,encoded).putInt("revision",next).commit()
   }.getOrDefault(false)
  }
 }
 fun read():String?=synchronized(lock) {
  runCatching {
   val encoded=prefs.getString(KEY_NAME,null)?:return@synchronized null
   val data=Base64.decode(encoded,Base64.NO_WRAP)
   if(data.size<29)return@synchronized null
   val cipher=Cipher.getInstance("AES/GCM/NoPadding")
   cipher.init(Cipher.DECRYPT_MODE,secret(),GCMParameterSpec(128,data.copyOfRange(0,12)))
   val key=String(cipher.doFinal(data.copyOfRange(12,data.size)),Charsets.UTF_8)
   key.takeIf(::validKey)
  }.getOrNull()
 }
 fun clear():Boolean=synchronized(lock) {
  // Keep a revision marker so negative metadata search cache can re-run.
  prefs.edit().remove(KEY_NAME).putInt("revision",revision()+1).commit()
 }
}
