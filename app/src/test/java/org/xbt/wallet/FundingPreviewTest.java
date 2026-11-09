package org.xbt.wallet;
import org.junit.Test;
import static org.junit.Assert.*;
public class FundingPreviewTest {
    @Test public void privateAllFundsSubtractsFee() {
        FundingPreview p = new FundingPreview(100000,100000,100000,1,0,false,273,1);
        assertEquals(123,p.fee); assertEquals(0,p.reserve); assertEquals(99877,p.capacity);
    }
    @Test public void publicPreservesReserveAndChangeFee() {
        FundingPreview p = new FundingPreview(100000,100000,100000,1,0,true,273,1);
        assertEquals(166,p.fee); assertEquals(10000,p.reserve); assertEquals(89834,p.capacity);
    }
    @Test public void otherFundsCanCoverReserve() {
        FundingPreview p = new FundingPreview(100000,120000,100000,1,10000,true,273,1);
        assertEquals(0,p.reserve); assertEquals(99877,p.capacity);
    }
    @Test public void privateStillPreservesExistingReserve() {
        assertEquals(10000,new FundingPreview(100000,100000,100000,1,10000,false,273,1).reserve);
    }
    @Test public void reserveIsCapped() {
        assertEquals(100000,new FundingPreview(200000,200000,200000,1,100000,true,273,1).reserve);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsOverspending() {
        new FundingPreview(100000,100000,100001,1,0,false,273,1);
    }
}
