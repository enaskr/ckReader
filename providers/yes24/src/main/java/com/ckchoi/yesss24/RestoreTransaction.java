package com.ckchoi.yesss24;

/** Keep the rollback journal until every destination and the reloaded client are valid. */
public final class RestoreTransaction {
 public interface Step { void run() throws Exception; }
 public static void run(Step saveJournal,Step writeVault,Step writeSettings,Step verify,Step rollback,Step clearJournal)throws Exception {
  saveJournal.run();
  try {writeVault.run();writeSettings.run();verify.run();}
  catch(Exception failure){
   try{rollback.run();}catch(Exception recovery){failure.addSuppressed(recovery);}
   throw failure;
  }
  clearJournal.run();
 }
}
