package org.xbt.wallet;
import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;

/** Draft cancellation tests only touch the disposable test application. */
public class WalletSetupTest {
    @Test public void cancelsDraftsAndReturnsToTheirActualParent() throws Exception {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("org.xbt.wallet.profiletest",c.getPackageName());
        String parent=WalletProfiles.create(c);WalletProfiles.activate(c,parent);
        WalletProfiles.settings(c,parent).edit().putString("wallet-name","Original wallet").putString("mode","remote").putBoolean("modeChoiceMade",true).commit();
        String config=new org.json.JSONObject().put("endpoint","https://127.0.0.1:1").put("rune","abc").put("nodeId","02"+"ab".repeat(32)).toString();
        new Vault(c,parent).put("remote-config",config);
        int count=WalletProfiles.ids(c).size();
        for(boolean enteredEasy:new boolean[]{false,true}) {
            String draft=WalletProfiles.createDraft(c);WalletProfiles.activate(c,draft);
            assertTrue(WalletProfiles.isDraft(c,draft));
            if(enteredEasy){
                WalletProfiles.settings(c,draft).edit().putString("mode","easy").putBoolean("modeChoiceMade",true).putString("wallet-name","Unfinished name").commit();
                File data=WalletProfiles.directory(c,draft,"mainnet");assertTrue(data.mkdirs());Files.write(new File(data,"partial-startup.db").toPath(),new byte[]{1});
            }
            WalletProfiles.cancelDraft(c,draft);assertEquals(parent,WalletProfiles.active(c));
            assertEquals(count,WalletProfiles.ids(c).size());assertFalse(WalletProfiles.ids(c).contains(draft));
            WalletProfiles.finishDeletions(c);assertTrue(WalletProfiles.settings(c,draft).getAll().isEmpty());
            assertFalse(WalletProfiles.directory(c,draft,"mainnet").exists());
            assertEquals(config,new Vault(c,parent).get("remote-config"));
        }
        // Once setup commits to generating keys, Back must never discard the wallet.
        String committed=WalletProfiles.createDraft(c);WalletProfiles.activate(c,committed);
        WalletProfiles.completeSetup(c,committed);assertFalse(WalletProfiles.isDraft(c,committed));
        try{WalletProfiles.cancelDraft(c,committed);fail("Completed wallet discarded");}catch(Exception expected){assertTrue(WalletProfiles.ids(c).contains(committed));}
        WalletProfiles.beginDeletion(c,committed);WalletProfiles.finishDeletions(c);WalletProfiles.activate(c,parent);
        String guarded=WalletProfiles.createDraft(c);WalletProfiles.activate(c,guarded);
        new Vault(c,guarded).put("seed","test-only fake seed");
        try{WalletProfiles.cancelDraft(c,guarded);fail("Keys discarded by cancellation");}catch(Exception expected){assertEquals("test-only fake seed",new Vault(c,guarded).get("seed"));}
        WalletProfiles.beginDeletion(c,guarded);WalletProfiles.finishDeletions(c);WalletProfiles.activate(c,parent);
        assertEquals(count,WalletProfiles.ids(c).size());
    }
}
