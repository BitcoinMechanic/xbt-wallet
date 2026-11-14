package org.xbt.wallet;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Base64;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.PBEKeySpec;
import android.security.keystore.*;

/** App-wide UI lock; node monitoring continues. Never stores the PIN itself. */
final class WalletPin {
    private static boolean unlocked;
    private static long touched;
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("wallet-pin",0); }
    static boolean enabled(Context c) { return prefs(c).contains("hash"); }
    static long timeout(Context c) { return prefs(c).getLong("timeout",900000); }
    static boolean restartOnly(Context c) { return prefs(c).getBoolean("restart-only",false); }
    static boolean locked(Context c) {
        return enabled(c) && (!unlocked || (!restartOnly(c) && SystemClock.elapsedRealtime()-touched >= timeout(c)));
    }
    static void touch(Context c) { if (!locked(c)) touched=SystemClock.elapsedRealtime(); }
    static void unlock() { unlocked=true; touched=SystemClock.elapsedRealtime(); }
    static void lock() { unlocked=false; }
    private static byte[] verifier(String pin, byte[] salt) throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias("wallet-pin-verifier")) {
            KeyGenerator generator=KeyGenerator.getInstance("HmacSHA256","AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("wallet-pin-verifier",KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256).build()); generator.generateKey();
        }
        PBEKeySpec spec=new PBEKeySpec(pin.toCharArray(),salt,120000,256);
        byte[] derived;
        try { derived=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
        Mac mac=Mac.getInstance("HmacSHA256"); mac.init(store.getKey("wallet-pin-verifier",null));
        try { return mac.doFinal(derived); } finally { java.util.Arrays.fill(derived,(byte)0); }
    }
    static void set(Context c,String pin) throws Exception {
        if(!pin.matches("[0-9]{6,12}"))throw new Exception("Use 6–12 digits.");
        byte[] salt=new byte[32]; new SecureRandom().nextBytes(salt);
        byte[] hash=verifier(pin,salt);
        if(!prefs(c).edit().putString("salt",Base64.encodeToString(salt,Base64.NO_WRAP))
                .putString("hash",Base64.encodeToString(hash,Base64.NO_WRAP))
                .remove("failures").remove("retry").commit())throw new Exception("Could not save PIN.");
        unlock();
    }
    static synchronized boolean verify(Context c,String pin) throws Exception {
        SharedPreferences p=prefs(c);
        long wait=p.getLong("retry",0)-System.currentTimeMillis();
        if(wait>0)throw new Exception("Try again in "+((wait+999)/1000)+" seconds.");
        if(!enabled(c))return false;
        boolean valid=MessageDigest.isEqual(verifier(pin,Base64.decode(p.getString("salt",""),Base64.NO_WRAP)),
                Base64.decode(p.getString("hash",""),Base64.NO_WRAP));
        int failures=valid?0:Math.min(20,p.getInt("failures",0)+1);
        long delay=failures<5?0:Math.min(300000L,30000L*(1L<<Math.min(4,failures-5)));
        if(!p.edit().putInt("failures",failures).putLong("retry",valid?0:System.currentTimeMillis()+delay).commit())
            throw new Exception("Could not save PIN attempt.");
        return valid;
    }
    static void remove(Context c) throws Exception {
        if(!prefs(c).edit().clear().commit())throw new Exception("Could not remove PIN.");
        unlocked=false;
    }
}
