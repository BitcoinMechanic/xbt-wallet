package org.xbt.wallet;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Collections;
import lnrpc.LightningOuterClass;
public class DepositDetectionTest {
    private LightningOuterClass.TransactionDetails tx(String hash, String address, int confirmations) {
        return LightningOuterClass.TransactionDetails.newBuilder().addTransactions(
            LightningOuterClass.Transaction.newBuilder().setTxHash(hash).setAmount(1000).setNumConfirmations(confirmations)
                .addOutputDetails(LightningOuterClass.OutputDetail.newBuilder().setAddress(address).setAmount(1000).setIsOurAddress(true))).build();
    }
    @Test public void detectsUnconfirmedAndConfirmedReceipts() {
        assertTrue(DepositDetection.received("receive", Collections.emptySet(), tx("new", "receive", 0)));
        assertTrue(DepositDetection.received("receive", Collections.emptySet(), tx("new", "receive", 1)));
    }
    @Test public void ignoresOldUnrelatedAndConflictedTransactions() {
        assertFalse(DepositDetection.received("receive", Collections.singleton("old"), tx("old", "receive", 1)));
        assertFalse(DepositDetection.received("receive", Collections.emptySet(), tx("new", "other", 1)));
        assertFalse(DepositDetection.received("receive", Collections.emptySet(), tx("new", "receive", -1)));
    }
}
