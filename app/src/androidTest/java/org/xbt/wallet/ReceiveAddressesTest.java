package org.xbt.wallet;

import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import static org.junit.Assert.*;

/** Isolated preferences only: never creates/resets a wallet or sends funds. */
public class ReceiveAddressesTest {
    private SharedPreferences prefs;
    @Before public void setup() {
        prefs = InstrumentationRegistry.getInstrumentation().getTargetContext()
            .getSharedPreferences("receive-address-tests", 0);
        prefs.edit().clear().commit();
    }
    @After public void cleanup() { prefs.edit().clear().commit(); }
    @Test public void neverReissuesRecordedOrLegacyAddresses() throws Exception {
        prefs.edit().putString("deposit", "old").commit();
        try { ReceiveAddresses.fresh(prefs, () -> "old"); fail(); } catch (Exception expected) {}
        assertEquals("new1", ReceiveAddresses.fresh(prefs, () -> "new1"));
        assertFalse(prefs.contains("deposit"));
        assertEquals("new2", ReceiveAddresses.fresh(prefs, () -> "new2"));
        // Re-read persisted preferences, as after the form or app has been reopened.
        SharedPreferences reopened = InstrumentationRegistry.getInstrumentation().getTargetContext()
            .getSharedPreferences("receive-address-tests", 0);
        for (String address : new String[]{"old", "new1", "new2"}) {
            try { ReceiveAddresses.fresh(reopened, () -> address); fail(address); } catch (Exception expected) {}
        }
    }
    @Test public void newRequestsDoNotReplaceOlderLabels() throws Exception {
        prefs.edit().putString("receive-label-address", "old").putString("receive-label", "Legacy")
            .putString(ReceiveAddresses.LABEL_PREFIX + "new1", "First")
            .putString(ReceiveAddresses.LABEL_PREFIX + "new2", "Second").commit();
        ReceiveAddresses.fresh(prefs, () -> "new3");
        assertEquals("Legacy", ReceiveAddresses.labels(prefs).get("old"));
        assertEquals("First", ReceiveAddresses.labels(prefs).get("new1"));
        assertEquals("Second", ReceiveAddresses.labels(prefs).get("new2"));
        assertFalse(ReceiveAddresses.labels(prefs).containsKey("new3"));
        prefs.edit().putString(ReceiveAddresses.LABEL_PREFIX + "new1", "").commit();
        assertFalse(ReceiveAddresses.labels(prefs).containsKey("new1"));
        assertEquals("Second", ReceiveAddresses.labels(prefs).get("new2"));
    }
}
