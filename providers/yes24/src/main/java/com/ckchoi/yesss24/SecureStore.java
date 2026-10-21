package com.ckchoi.yesss24;
import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
public final class SecureStore {
 private final Context context; private final String alias,prefsName;
 public SecureStore(Context c){this(c,"crema.session.v1","vault");}
 public SecureStore(Context c,String alias,String prefsName){context=c;this.alias=alias;this.prefsName=prefsName;}
 private javax.crypto.SecretKey key()throws Exception {KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(!ks.containsAlias(alias)){KeyGenerator gen=KeyGenerator.getInstance("AES","AndroidKeyStore");gen.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();}return (javax.crypto.SecretKey)ks.getKey(alias,null);}
 public void save(String text)throws Exception {Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());String encoded=Base64.encodeToString(c.getIV(),2)+":"+Base64.encodeToString(c.doFinal(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),2);if(!context.getSharedPreferences(prefsName,0).edit().putString("session",encoded).commit())throw new Exception("로그인 상태 저장 실패");}
 public String load()throws Exception {String s=context.getSharedPreferences(prefsName,0).getString("session","");if(s.isEmpty())return "{}";String[] p=s.split(":");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(p[0],2)));return new String(c.doFinal(Base64.decode(p[1],2)),java.nio.charset.StandardCharsets.UTF_8);}
 public void clear(){context.getSharedPreferences(prefsName,0).edit().clear().apply();}
}
