package org.xbt.wallet;
import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.security.KeyStore;

/** Destructive tests are restricted to the dedicated disposable package; no node is started. */
public class WalletRemovalTest {
    @Test public void deletionIsIsolatedResumableAndDoesNotResurrectLegacyWallet() throws Exception {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("org.xbt.wallet.profiletest",c.getPackageName());
        assertEquals(java.util.Collections.singletonList(WalletProfiles.LEGACY),WalletProfiles.ids(c));
        String original=WalletProfiles.LEGACY, other=WalletProfiles.create(c);
        SharedPreferences originalSettings=WalletProfiles.settings(c,original);
        originalSettings.edit().putString("wallet-name","Delete fixture").commit();
        new Vault(c,original).put("seed","original fixture");new Vault(c,other).put("seed","surviving fixture");
        File legacyMain=WalletProfiles.directory(c,original,"mainnet"),legacyReg=WalletProfiles.directory(c,original,"regtest");
        assertTrue(legacyMain.mkdirs());assertTrue(legacyReg.mkdirs());Files.write(new File(legacyMain,"wallet.db").toPath(),new byte[]{1});
        File survivor=WalletProfiles.directory(c,other,"mainnet");assertTrue(survivor.mkdirs());File survivorDb=new File(survivor,"wallet.db");Files.write(survivorDb.toPath(),new byte[]{2});
        // A symlink inside the removed profile must not erase another profile.
        Files.createSymbolicLink(new File(legacyMain,"link").toPath(),survivor.toPath());
        try{WalletProfiles.beginDeletion(c,other);fail("Inactive wallet removed");}catch(Exception expected){assertTrue(survivorDb.exists());}
        WalletProfiles.beginDeletion(c,original);
        assertTrue(WalletProfiles.hasPendingDeletion(c));assertEquals(other,WalletProfiles.active(c));assertFalse(WalletProfiles.ids(c).contains(original));
        // Simulate interruption after a subset of files were erased.
        assertTrue(new File(legacyMain,"wallet.db").delete());
        WalletProfiles.finishDeletions(c);WalletProfiles.finishDeletions(c);
        assertFalse(legacyMain.exists());assertFalse(legacyReg.exists());assertTrue(survivorDb.exists());
        assertEquals("surviving fixture",new Vault(c,other).get("seed"));assertNull(new Vault(c,original).get("seed"));assertTrue(originalSettings.getAll().isEmpty());
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);assertFalse(keys.containsAlias(WalletProfiles.keyAlias(original)));assertTrue(keys.containsAlias(WalletProfiles.keyAlias(other)));
        assertFalse(WalletProfiles.hasPendingDeletion(c));
        // Removing the last wallet creates a fresh, unnamed setup profile, never the old default ID.
        WalletProfiles.beginDeletion(c,other);String fresh=WalletProfiles.active(c);
        assertNotEquals(other,fresh);assertNotEquals(original,fresh);assertEquals(java.util.Collections.singletonList(fresh),WalletProfiles.ids(c));
        WalletProfiles.finishDeletions(c);assertFalse(survivor.exists());assertNull(new Vault(c,other).get("seed"));
        assertFalse(WalletProfiles.settings(c,fresh).contains("mode"));assertFalse(WalletProfiles.settings(c,fresh).contains("wallet-name"));
        assertFalse(WalletProfiles.ids(c).contains(original));assertNull(new Vault(c,fresh).get("seed"));
    }
    @Test public void prepareDisposableUiFixture() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("prepare_ui")));
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("org.xbt.wallet.profiletest",c.getPackageName());
        String selected=WalletProfiles.active(c), keep=WalletProfiles.create(c);
        WalletProfiles.settings(c,keep).edit().putString("wallet-name","Keep UI fixture").commit();
        WalletProfiles.settings(c,selected).edit().putString("wallet-name","Delete UI fixture").putString("mode","remote").putBoolean("modeChoiceMade",true).commit();
        new Vault(c,selected).put("remote-config",new org.json.JSONObject().put("endpoint","https://127.0.0.1:1").put("rune","abc").put("nodeId","02"+"ab".repeat(32)).toString());
    }
}
