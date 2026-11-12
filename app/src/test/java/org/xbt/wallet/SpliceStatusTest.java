package org.xbt.wallet;

import org.junit.Test;
import static org.junit.Assert.*;
import lnrpc.LightningOuterClass.Channel;
import lnrpc.LightningOuterClass.PendingSplice;

public class SpliceStatusTest {
    private Channel pending(PendingSplice.Builder s) { return Channel.newBuilder().setPendingSplice(s).build(); }
    @Test public void remoteContributionIsReceivableNotSpendable() {
        Channel c = pending(PendingSplice.newBuilder().setRemoteDeltaSat(1000000).setTxid("tx").setConfirmations(3).setLockDepth(21));
        assertEquals("Splice pending\nReceivable: +1,000,000 sats\nConfirmations: 3/21", SpliceStatus.summary(c));
        assertFalse(SpliceStatus.summary(c).contains("Spendable"));
    }
    @Test public void localWithdrawalAndPeerLockAreDistinct() {
        Channel c = pending(PendingSplice.newBuilder().setLocalDeltaSat(-500000).setTxid("tx").setConfirmations(22).setLockDepth(21).setWeLocked(true));
        assertTrue(SpliceStatus.summary(c).contains("Spendable: -500,000 sats"));
        assertTrue(SpliceStatus.summary(c).contains("22/21 · waiting for peer"));
    }
    @Test public void failedAndUnsignedSplicesNeverClaimConfirmationProgress() {
        assertEquals("Negotiating splice", SpliceStatus.progress(PendingSplice.getDefaultInstance()));
        assertEquals("This splice can no longer confirm.", SpliceStatus.progress(PendingSplice.newBuilder().setDead(true).build()));
        assertEquals("Broadcast rejected: missing input", SpliceStatus.progress(PendingSplice.newBuilder().setRelayStatus("rejected").setRelayReason("missing input").build()));
        assertEquals("", SpliceStatus.summary(Channel.getDefaultInstance()));
    }
    @Test public void amountFormattingFollowsGlobalUnit() {
        Channel c = pending(PendingSplice.newBuilder().setRemoteDeltaSat(1000000));
        assertTrue(SpliceStatus.summary(c, amount -> "≈ 7.93 USD").contains("Receivable: ≈ +7.93 USD"));
    }
    @Test public void originalFundingPreservesLabelsAcrossSplice() {
        assertEquals("old:0", SpliceStatus.identity(Channel.newBuilder().setOriginalChannelPoint("old:0").setChannelPoint("new:1").build()));
        assertEquals("old:0", SpliceStatus.identity(Channel.newBuilder().setChannelPoint("old:0").build()));
    }
}
