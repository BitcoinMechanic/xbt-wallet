package org.xbt.wallet;
import org.junit.Test;
import static org.junit.Assert.*;
public class OnChainPaymentTest {
    private static final String ADDRESS = "bcrt1qd8vd7alsyn5z2j97mfrxlpgf3n0dfz62sg36zl";
    @Test public void receiveRequestIncludesOptionalExactAmount() {
        assertEquals(ADDRESS, OnChainPayment.receiveRequest(ADDRESS, ""));
        assertEquals("bitcoin:" + ADDRESS + "?amount=0.00000001", OnChainPayment.receiveRequest(ADDRESS, "1"));
        assertEquals(Long.valueOf(123456), OnChainPayment.parse(OnChainPayment.receiveRequest(ADDRESS, "123456")).amount);
        assertEquals("bitcoin:" + ADDRESS + "?amount=1", OnChainPayment.receiveRequest(ADDRESS, "100000000"));
    }
    @Test(expected=IllegalArgumentException.class) public void receiveRequestRejectsZeroAmount() {
        OnChainPayment.receiveRequest(ADDRESS, "0");
    }
    @Test public void uriAmountIsExactSatoshis() {
        OnChainPayment.Request request = OnChainPayment.parse("bitcoin:" + ADDRESS + "?amount=0.00123456&label=Coffee%20shop");
        assertEquals(ADDRESS, request.address);
        assertEquals(Long.valueOf(123456), request.amount);
        assertEquals(Long.valueOf(1), OnChainPayment.parse("xbt:" + ADDRESS + "?amount=0.00000001").amount);
        assertNull(OnChainPayment.parse(ADDRESS).amount);
    }
    @Test public void rejectsAmbiguousOrUnrepresentableAmounts() {
        for (String query : new String[]{"amount=0.000000001", "amount=1&amount=2", "amount=-1", "amount=1e-3", "req-other=1", "amount=21000001"}) {
            try { OnChainPayment.parse("bitcoin:" + ADDRESS + "?" + query); fail(query); }
            catch (IllegalArgumentException expected) {}
        }
    }
    @Test public void manualAmountIsNeverReducedByFee() {
        OnChainPayment plan = new OnChainPayment(100000,100000,0,273,1,124,50000,2,false);
        assertEquals(50000, plan.amount);
        assertEquals(305, plan.fee);
        assertEquals(50305, plan.total);
    }
    @Test public void manualPreviewWorksBeforeAddressAndRefinesAfterward() {
        OnChainPayment preliminary = new OnChainPayment(100000,100000,0,273,1,
            OnChainPayment.previewOutputWeight(""),50000,2,false);
        assertEquals(50000, preliminary.amount);
        assertEquals(329, preliminary.fee);
        assertEquals(50329, preliminary.total);
        OnChainPayment addressed = new OnChainPayment(100000,100000,0,273,1,
            OnChainPayment.previewOutputWeight(ADDRESS),50000,2,false);
        assertEquals(preliminary.amount, addressed.amount);
        assertEquals(305, addressed.fee);
    }
    @Test(expected=IllegalArgumentException.class) public void invalidAddressIsNotTreatedAsMissing() {
        OnChainPayment.previewOutputWeight("invalid");
    }
    @Test public void maxDeductsFeeAndConsumesSelection() {
        OnChainPayment plan = new OnChainPayment(100000,200000,0,273,1,124,0,2,true);
        assertEquals(219, plan.fee);
        assertEquals(99781, plan.amount);
        assertEquals(100000, plan.total);
    }
    @Test public void maxRetainsBackendReserveWhenNeeded() {
        OnChainPayment plan = new OnChainPayment(100000,105000,10000,273,1,124,0,2,true);
        assertEquals(10000, plan.reserve);
        assertEquals(89695, plan.amount);
        assertEquals(90000, plan.total);
    }
    @Test public void tinyChangeBecomesFeeNotReducedRecipient() {
        OnChainPayment plan = new OnChainPayment(100000,100000,0,273,1,124,99700,1,false);
        assertEquals(99700, plan.amount);
        assertEquals(300, plan.fee);
        assertEquals(100000, plan.total);
    }
    @Test(expected=IllegalArgumentException.class) public void noSilentAdditionalInputs() {
        new OnChainPayment(100000,200000,0,273,1,124,100000,1,false);
    }
    @Test public void unfundedEstimateRefinesWithAddressAndFeeRate() {
        assertEquals(329, OnChainPayment.unfundedFee("", 2));
        assertEquals(305, OnChainPayment.unfundedFee(ADDRESS, 2));
        assertEquals(611, OnChainPayment.unfundedFee(ADDRESS, 4));
    }
    @Test(expected=IllegalArgumentException.class) public void unfundedWalletStillCannotCreatePayment() {
        new OnChainPayment(0, 0, 0, 0, 0, 124, 50000, 2, false);
    }
    @Test public void recipientOutputSizes() {
        assertEquals(124, OnChainPayment.outputWeight(ADDRESS));
    }
}
