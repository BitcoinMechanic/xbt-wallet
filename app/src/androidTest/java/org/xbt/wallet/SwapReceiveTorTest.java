package org.xbt.wallet;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Explicit read-only live API probe. Never creates an invoice, swap or payment. */
public class SwapReceiveTorTest {
    @Test public void configuredControllerAuthenticatesStatusOverTor() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences prefs=context.getSharedPreferences("wallet",0);
        String provider=prefs.getString("receive-provider",""),api=prefs.getString("receive-api-"+provider,""),token=prefs.getString("receive-key-"+provider,"");
        assertTrue("Configure an onion receive API before this explicit integration probe",ControllerAddress.onion(ControllerAddress.origin(api)));
        JSONObject request=new JSONObject().put("action","status").put("invoice","lnbc"+"q".repeat(150)).put("environment",prefs.getString("network","regtest")).put("provider",provider);
        TorTransport tor=new TorTransport();
        try {
            tor.start(context,status->{});
            try { SwapControllers.receive(api,token,request,true);fail("unknown read-only status succeeded"); }
            catch(Exception e) { assertTrue("Expected an authenticated not-found reply: "+e.getMessage(),e.getMessage().contains("Code: wallet_receive_not_found"));assertFalse(e.getMessage().contains(token)); }
            try { SwapControllers.receive(api,"0".repeat(64),request,true);fail("wrong access key accepted"); }
            catch(Exception e) { assertTrue(e.getMessage().contains("access denied")); }
        } finally { tor.close(context); }
    }
}
