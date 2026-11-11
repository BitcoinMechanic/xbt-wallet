package org.xbt.wallet;
import android.content.ClipData;
import android.content.ClipboardManager;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assert.assertTrue;

/** Opt-in UI fixture: copies a supplied public invoice, never pays or modifies wallet data. */
public class InvoiceClipboardTest {
    @Test public void copyInvoiceForManualReview() {
        String invoice = InstrumentationRegistry.getArguments().getString("test_invoice");
        assumeNotNull(invoice);
        assertTrue(invoice.matches("(?i)ln(bc|tb|bcrt)[a-z0-9]+"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            InstrumentationRegistry.getInstrumentation().getTargetContext().getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText("Test invoice", invoice)));
    }
}
